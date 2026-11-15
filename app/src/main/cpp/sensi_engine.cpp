/*
 * SensiTune native engine.
 *
 * Flow: open the real touchscreen -> create a uinput virtual touchscreen with the
 * exact same capabilities -> verify the virtual node exists -> ONLY THEN grab the
 * real device -> read events, scale relative movement per slot, write to virtual.
 *
 * Safety rules baked in:
 *  - no grab unless the virtual device was created and verified,
 *  - watchdog releases the grab if the event loop stalls for >1s,
 *  - heartbeat timeout (3s) releases the grab,
 *  - both volume keys held for 2s release the grab (read only, never grabbed),
 *  - fatal signal handler ungrabs and closes fds,
 *  - every early return path releases before returning.
 */

#include "sensi_engine.h"
#include "sensi_abs.h"
#include "sensi_json.h"

#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <fstream>
#include <linux/input.h>
#include <linux/uinput.h>
#include <mutex>
#include <poll.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <string>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>
#include <vector>

#define LOG_TAG "SensiEngine"
#define LOG_RING_CAPACITY 2000
#define VIRT_NAME "SensiTune Virtual Touch"
#define MAX_SLOTS 32
#define MAX_EMERGENCY_KEYS 4
#define READ_BUF_EVENTS 64
#define PACKET_MAX_EVENTS 256

// ---------------------------------------------------------------------------
// Logging: logcat + in-memory ring buffer with seq/timestamp/level/thread.
// ---------------------------------------------------------------------------

enum LogLevel { LVL_DEBUG = 0, LVL_INFO = 1, LVL_WARN = 2, LVL_ERROR = 3 };

struct LogEntry {
    int64_t seq = 0;
    int64_t ts_ms = 0;
    int level = LVL_INFO;
    char thread[24] = {0};
    char msg[640] = {0};
};

static LogEntry g_logs[LOG_RING_CAPACITY];
static int64_t g_log_seq = 1;      // next seq to assign
static int g_log_count = 0;        // number of valid entries (<= capacity)
static std::mutex g_log_mutex;

static void log_message(int level, const char *fmt, ...)
        __attribute__((format(printf, 2, 3)));

static void log_message(int level, const char *fmt, ...) {
    char msg[520];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);

    int prio = ANDROID_LOG_INFO;
    const char *lvl_name = "I";
    switch (level) {
        case LVL_DEBUG: prio = ANDROID_LOG_DEBUG; lvl_name = "D"; break;
        case LVL_INFO: prio = ANDROID_LOG_INFO; lvl_name = "I"; break;
        case LVL_WARN: prio = ANDROID_LOG_WARN; lvl_name = "W"; break;
        default: prio = ANDROID_LOG_ERROR; lvl_name = "E"; break;
    }
    __android_log_print(prio, LOG_TAG, "%s", msg);

    char tname[24] = {0};
    pthread_getname_np(pthread_self(), tname, sizeof(tname));
    if (tname[0] == '\0') snprintf(tname, sizeof(tname), "unnamed");

    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    int64_t now = (int64_t) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;

    std::lock_guard<std::mutex> lock(g_log_mutex);
    LogEntry &e = g_logs[g_log_seq % LOG_RING_CAPACITY];
    e.seq = g_log_seq++;
    e.ts_ms = now;
    e.level = level;
    snprintf(e.thread, sizeof(e.thread), "%s", tname);
    snprintf(e.msg, sizeof(e.msg), "%s", msg);
    if (g_log_count < LOG_RING_CAPACITY) g_log_count++;
    (void) lvl_name;
}

#define LOGD(...) log_message(LVL_DEBUG, __VA_ARGS__)
#define LOGI(...) log_message(LVL_INFO, __VA_ARGS__)
#define LOGW(...) log_message(LVL_WARN, __VA_ARGS__)
#define LOGE(...) log_message(LVL_ERROR, __VA_ARGS__)

static std::string json_escape(const char *s) {
    return s ? sensitune::sensi_json_escape(std::string(s)) : std::string();
}

static std::string json_escape(const std::string &s) { return json_escape(s.c_str()); }

// ---------------------------------------------------------------------------
// Engine state
// ---------------------------------------------------------------------------

enum EngineState { STATE_IDLE = 0, STATE_READY = 1, STATE_ACTIVE = 2, STATE_ERROR = 3 };

static std::atomic<int> g_state{STATE_IDLE};
static std::atomic<bool> g_running{false};
static std::atomic<bool> g_stop{false};
static std::atomic<bool> g_grab_active{false};
static std::atomic<bool> g_ui_destroyed{false};
static std::atomic<float> g_kx{1.0f};
static std::atomic<float> g_ky{1.0f};
static std::atomic<int64_t> g_last_heartbeat_ms{0};
static std::atomic<int64_t> g_last_loop_ms{0};
static std::atomic<int64_t> g_started_ms{0};
static std::atomic<int> g_src_fd{-1};
static std::atomic<int> g_ui_fd{-1};
static std::atomic<int> g_key_fds[MAX_EMERGENCY_KEYS];
static std::atomic<int> g_key_fd_count{0};
static std::atomic<int> g_max_slots{10};
static std::atomic<bool> g_has_slot_axis{true};

static std::atomic<uint64_t> g_ev_read{0};
static std::atomic<uint64_t> g_ev_written{0};
static std::atomic<uint64_t> g_packets_written{0};
static std::atomic<uint64_t> g_packets_dropped{0};
static std::atomic<uint64_t> g_eagain{0};
static std::atomic<uint64_t> g_latency_sum_us{0};
static std::atomic<uint64_t> g_latency_max_us{0};
static std::atomic<uint64_t> g_events_per_sec{0};

static std::mutex g_op_mutex;       // start/stop serialization
static std::mutex g_release_mutex;  // emergency release serialization
static std::mutex g_err_mutex;
static std::string g_last_error;
static std::atomic<int> g_last_error_code{0};

static std::thread g_reader_thread;
static std::thread g_watchdog_thread;
static std::thread g_key_thread;

static int g_abs_min_x = 0, g_abs_max_x = 0;
static int g_abs_min_y = 0, g_abs_max_y = 0;
static char g_device_path[64] = {0};
static char g_device_name[128] = {0};

// Signal-handler-safe copies.
static volatile sig_atomic_t g_sig_src_fd = -1;
static volatile sig_atomic_t g_sig_ui_fd = -1;

static void set_error(int code, const std::string &msg) {
    g_last_error_code.store(code);
    std::lock_guard<std::mutex> lock(g_err_mutex);
    g_last_error = msg;
    LOGE("error %d (%s): %s", code, sensi_strerror(code), msg.c_str());
}

static int64_t now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static int64_t now_us() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000000 + ts.tv_nsec / 1000;
}

const char *sensi_strerror(int code) {
    switch (code) {
        case SENSI_OK: return "ok";
        case SENSI_ERR_NO_TOUCH_DEVICE: return "no candidate touchscreen found";
        case SENSI_ERR_UINPUT_OPEN: return "cannot open /dev/uinput";
        case SENSI_ERR_UINPUT_SETUP: return "uinput setup failed";
        case SENSI_ERR_UINPUT_CREATE: return "UI_DEV_CREATE failed";
        case SENSI_ERR_UINPUT_NODE_MISSING: return "virtual device node did not appear";
        case SENSI_ERR_GRAB: return "EVIOCGRAB failed";
        case SENSI_ERR_BAD_ARG: return "bad argument";
        case SENSI_ERR_NOT_READY: return "native library not ready";
        case SENSI_ERR_INTERNAL: return "internal error";
        case SENSI_ERR_NOT_SUPPORTED: return "not supported on this device";
        default: return "unknown error";
    }
}

// ---------------------------------------------------------------------------
// Bitmap helpers
// ---------------------------------------------------------------------------

static size_t bitmap_words(int max_code) {
    return ((size_t) max_code + 1 + 8 * sizeof(unsigned long) - 1) /
           (8 * sizeof(unsigned long));
}

static bool bit_test(const std::vector<unsigned long> &bits, int code) {
    if (code < 0) return false;
    size_t word = (size_t) code / (8 * sizeof(unsigned long));
    if (word >= bits.size()) return false;
    return (bits[word] >> (code % (8 * sizeof(unsigned long)))) & 1UL;
}

static bool query_bits(int fd, unsigned long request, int max_code,
                       std::vector<unsigned long> *out) {
    out->assign(bitmap_words(max_code), 0);
    int rc = ioctl(fd, request, out->data());
    if (rc < 0) return false;
    return true;
}

// ---------------------------------------------------------------------------
// Touchscreen discovery
// ---------------------------------------------------------------------------

struct TouchCandidate {
    std::string path;
    std::string name;
    int fd = -1;
    int min_x = 0, max_x = 0, min_y = 0, max_y = 0;
    int res_x = 0, res_y = 0;   // logged only; never used for filtering/ranking
    int max_slots = 10;
    bool has_slot_axis = true;
    bool direct = false;        // INPUT_PROP_DIRECT: ranking preference only
    unsigned long long area = 0;
    std::vector<unsigned long> abs_bits;
    std::vector<unsigned long> key_bits;
    input_id id = {};
};

static const char *abs_code_name(int code);

static const char *ev_code_name(int code) {
    switch (code) {
        case EV_SYN: return "EV_SYN";
        case EV_KEY: return "EV_KEY";
        case EV_REL: return "EV_REL";
        case EV_ABS: return "EV_ABS";
        case EV_MSC: return "EV_MSC";
        case EV_SW: return "EV_SW";
        case EV_LED: return "EV_LED";
        case EV_SND: return "EV_SND";
        case EV_REP: return "EV_REP";
        case EV_FF: return "EV_FF";
        case EV_PWR: return "EV_PWR";
        case EV_FF_STATUS: return "EV_FF_STATUS";
        default: return "EV_OTHER";
    }
}

static std::string ev_bits_summary(const std::vector<unsigned long> &bits) {
    std::string s;
    for (int code = 0; code <= EV_MAX; ++code) {
        if (!bit_test(bits, code)) continue;
        if (!s.empty()) s += ",";
        s += ev_code_name(code);
    }
    return s.empty() ? "none" : s;
}

static std::string hex_bytes(const uint8_t *bytes, size_t n) {
    std::string s;
    char part[4];
    for (size_t i = 0; i < n; ++i) {
        snprintf(part, sizeof(part), "%02x", bytes[i]);
        if (i) s += ' ';
        s += part;
    }
    return s;
}

/* The real axes of this node, from both the bitmap and the EVIOCGABS probes. */
static std::string abs_caps_summary(const sensitune::AbsCaps &caps,
                                   const sensitune::AbsEvaluation &eval) {
    std::string s;
    for (int code = 0; code <= ABS_MAX; ++code) {
        const sensitune::AxisProbe &a = caps.axes[code];
        if (!a.bit_set && !a.getabs_ok) continue;
        std::string part = abs_code_name(code);
        part += '(' + std::to_string(code) + ')';
        if (a.getabs_ok) {
            part += " min=" + std::to_string(a.minimum) +
                    " max=" + std::to_string(a.maximum) +
                    " res=" + std::to_string(a.resolution) +
                    " fuzz=" + std::to_string(a.fuzz) +
                    " flat=" + std::to_string(a.flat);
        } else {
            part += " EVIOCGABS_FAILED";
        }
        part += a.bit_set ? " [bitmask]" : " [probe-only]";
        if (eval.present[code] && !a.bit_set) part += " (virtual: copied)";
        if (!s.empty()) s += "; ";
        s += part;
    }
    return s.empty() ? "none" : s;
}

static bool read_candidate_info(int fd, const std::string &path, TouchCandidate *out) {
    char name[128] = {0};
    if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) < 0) {
        LOGW("scan %s: EVIOCGNAME failed: %s (errno=%d) - using \"unknown\"",
             path.c_str(), strerror(errno), errno);
        snprintf(name, sizeof(name), "unknown");
    }

    std::vector<unsigned long> ev_bits;
    if (!query_bits(fd, EVIOCGBIT(0, bitmap_words(EV_MAX)), EV_MAX, &ev_bits)) {
        LOGI("scan %s \"%s\": EVIOCGBIT(EV) failed: %s (errno=%d) -> rejected",
             path.c_str(), name, strerror(errno), errno);
        return false;
    }
    LOGI("scan %s \"%s\": EV bits = [%s]", path.c_str(), name,
         ev_bits_summary(ev_bits).c_str());
    if (!bit_test(ev_bits, EV_ABS)) {
        LOGI("scan %s \"%s\": no EV_ABS -> rejected", path.c_str(), name);
        return false;
    }

    // Read the ABS bitmap into an explicit byte buffer. The length handed to
    // EVIOCGBIT is (ABS_MAX / 8) + 1 bytes, the buffer is zeroed first and the
    // return value/errno are logged. Some drivers return success but a zero
    // bitmap, so this alone must never reject the device.
    uint8_t abs_bytes[sensitune::kAbsBitmapBytes] = {};
    errno = 0;
    int abs_rc = ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(abs_bytes)), abs_bytes);
    int abs_ioctl_errno = errno;
    LOGI("scan %s \"%s\": EVIOCGBIT(EV_ABS, len=%zu) ret=%d errno=%d (%s) bytes=[%s]",
         path.c_str(), name, sizeof(abs_bytes), abs_rc, abs_ioctl_errno,
         abs_rc < 0 ? strerror(abs_ioctl_errno) : "ok",
         hex_bytes(abs_bytes, sizeof(abs_bytes)).c_str());

    sensitune::AbsCaps caps;
    caps.ev_abs = true;
    caps.bitmask_ok = abs_rc >= 0;
    for (int code = 0; code < ABS_CNT; ++code) {
        caps.axes[code].bit_set =
                sensitune::abs_bit_test(abs_bytes, sizeof(abs_bytes), code);
    }

    // Probe every bitmap axis PLUS the interesting axes unconditionally, so a
    // broken/empty bitmap still yields real ranges for the virtual device.
    for (int code = 0; code < ABS_CNT; ++code) {
        const bool from_mask = caps.axes[code].bit_set;
        if (!from_mask && !sensitune::abs_probe_axis(code)) continue;

        input_absinfo ai = {};
        errno = 0;
        int rc = ioctl(fd, EVIOCGABS(code), &ai);
        int e = errno;
        if (rc == 0) {
            caps.axes[code].getabs_ok = true;
            caps.axes[code].minimum = ai.minimum;
            caps.axes[code].maximum = ai.maximum;
            caps.axes[code].resolution = ai.resolution;
            caps.axes[code].fuzz = ai.fuzz;
            caps.axes[code].flat = ai.flat;
        }
        LOGI("scan %s axis code=%d (%s) bitmask=%d EVIOCGABS ret=%d errno=%d (%s) "
             "min=%d max=%d res=%d fuzz=%d flat=%d",
             path.c_str(), code, abs_code_name(code), from_mask ? 1 : 0, rc, e,
             rc == 0 ? "ok" : strerror(e), rc == 0 ? ai.minimum : 0,
             rc == 0 ? ai.maximum : 0, rc == 0 ? ai.resolution : 0,
             rc == 0 ? ai.fuzz : 0, rc == 0 ? ai.flat : 0);
    }

    sensitune::AbsEvaluation eval = sensitune::evaluate_abs(caps);
    LOGI("scan %s \"%s\": ABS axes = [%s]", path.c_str(), name,
         abs_caps_summary(caps, eval).c_str());
    if (!eval.accepted) {
        LOGI("scan %s \"%s\": rejected: %s", path.c_str(), name, eval.reason);
        return false;
    }
    if (eval.probe_only[ABS_MT_POSITION_X] || eval.probe_only[ABS_MT_POSITION_Y]) {
        LOGW("scan %s \"%s\": ABS bitmap did not advertise POSITION_X/Y but "
             "EVIOCGABS returned real ranges - accepting via probe fallback",
             path.c_str(), name);
    }

    int props = 0;
    if (ioctl(fd, EVIOCGPROP(sizeof(props)), &props) < 0) {
        LOGW("scan %s: EVIOCGPROP failed: %s (errno=%d) - treating DIRECT as unset",
             path.c_str(), strerror(errno), errno);
        props = 0;
    }
    bool direct = (props & (1 << INPUT_PROP_DIRECT)) != 0;
    LOGI("scan %s \"%s\": props=0x%x INPUT_PROP_DIRECT=%d", path.c_str(), name, props,
         direct ? 1 : 0);

    out->path = path;
    out->name = name;
    out->fd = fd;
    out->direct = direct;
    out->min_x = eval.min_x;
    out->max_x = eval.max_x;
    out->min_y = eval.min_y;
    out->max_y = eval.max_y;
    out->res_x = eval.res_x;
    out->res_y = eval.res_y;
    ioctl(fd, EVIOCGID, &out->id);

    out->has_slot_axis = eval.has_slot_axis;
    out->max_slots = eval.max_slots;
    LOGI("scan %s \"%s\": slot axis=%d max_slots=%d (from %s)", path.c_str(), name,
         out->has_slot_axis ? 1 : 0, out->max_slots,
         eval.present[ABS_MT_SLOT] ? "EVIOCGABS" : "bitmap/default");

    unsigned long long w = (unsigned long long) (out->max_x - out->min_x);
    unsigned long long h = (unsigned long long) (out->max_y - out->min_y);
    out->area = (w > 0 && h > 0) ? w * h : 0;

    // Advertise exactly the axes that have real ranges, including ones the
    // bitmap omitted (slot/tracking id/pressure/touch major/width major).
    out->abs_bits.assign(bitmap_words(ABS_MAX), 0);
    int copied_axes = 0;
    for (int code = 0; code <= ABS_MAX; ++code) {
        if (!eval.present[code]) continue;
        out->abs_bits[(size_t) code / (8 * sizeof(unsigned long))] |=
                1UL << (code % (8 * sizeof(unsigned long)));
        copied_axes++;
    }
    LOGI("scan %s \"%s\": will copy %d abs axes to virtual device", path.c_str(),
         name, copied_axes);

    query_bits(fd, EVIOCGBIT(EV_KEY, bitmap_words(KEY_MAX)), KEY_MAX, &out->key_bits);

    LOGI("scan %s \"%s\": ACCEPTED direct=%d x=%d..%d y=%d..%d slots=%d "
         "area=%llu (res=%d/%d ignored)",
         path.c_str(), name, direct ? 1 : 0, out->min_x, out->max_x,
         out->min_y, out->max_y, out->max_slots, out->area, out->res_x, out->res_y);
    return true;
}

/*
 * Scans /dev/input/event0..31. Logs every candidate (accepted or rejected) with
 * its abs ranges. When keep_fds is true, candidate fds stay open; otherwise all
 * fds are closed before returning.
 */
static std::vector<TouchCandidate> scan_touch_devices(bool keep_fds, int *chosen_index) {
    std::vector<TouchCandidate> found;
    if (chosen_index) *chosen_index = -1;

    for (int i = 0; i < 32; ++i) {
        char path[64];
        snprintf(path, sizeof(path), "/dev/input/event%d", i);
        int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) {
            if (errno == EACCES || errno == EPERM) {
                LOGW("scan: open %s failed: %s (errno=%d) - node inaccessible",
                     path, strerror(errno), errno);
            } else if (errno != ENOENT && errno != ENODEV) {
                LOGD("scan: open %s failed: %s (errno=%d)", path, strerror(errno), errno);
            }
            continue;
        }
        TouchCandidate c;
        if (!read_candidate_info(fd, path, &c)) {
            close(fd);
            continue;
        }
        c.fd = fd;
        found.push_back(std::move(c));
    }

    if (!found.empty()) {
        size_t best = 0;
        for (size_t i = 1; i < found.size(); ++i) {
            const TouchCandidate &a = found[i];
            const TouchCandidate &b = found[best];
            if (a.direct != b.direct) {
                if (a.direct) best = i;
            } else if (a.area > b.area) {
                best = i;
            }
        }
        if (chosen_index) *chosen_index = (int) best;
        LOGI("selected %s \"%s\" (%zu candidate(s)): %s (area=%llu)",
             found[best].path.c_str(), found[best].name.c_str(), found.size(),
             found[best].direct ? "INPUT_PROP_DIRECT preferred"
                                : "no DIRECT candidate, largest X*Y area",
             found[best].area);
    } else {
        LOGW("no touchscreen candidate found in /dev/input/event0..31 "
             "(need EV_ABS + ABS_MT_POSITION_X/Y with max > min)");
    }

    if (!keep_fds) {
        for (auto &c : found) {
            if (c.fd >= 0) close(c.fd);
            c.fd = -1;
        }
    }
    return found;
}

// ---------------------------------------------------------------------------
// Virtual device creation + verification
// ---------------------------------------------------------------------------

struct VirtualVerify {
    bool ok = false;
    bool via_sysname = false;   // (a) UI_GET_SYSNAME -> sysfs path exists
    bool via_new_node = false;  // (b) new /dev/input node + (c) EVIOCGNAME match
    bool inotify_used = false;
    bool sysname_queried = false;
    std::string sysname;
    std::string node;
};

/* Snapshot + inotify watch that MUST be taken before UI_DEV_CREATE. */
struct InputWatch {
    std::vector<std::string> before;
    int ifd = -1;
    bool inotify_ok = false;
};

static bool path_exists(const std::string &path) {
    return access(path.c_str(), F_OK) == 0;
}

static std::vector<std::string> list_input_nodes(void) {
    std::vector<std::string> nodes;
    DIR *dir = opendir("/dev/input");
    if (!dir) {
        LOGW("verify: opendir(/dev/input) failed: %s (errno=%d)", strerror(errno), errno);
        return nodes;
    }
    struct dirent *ent = nullptr;
    while ((ent = readdir(dir)) != nullptr) {
        if (strncmp(ent->d_name, "event", 5) == 0) nodes.emplace_back(ent->d_name);
    }
    closedir(dir);
    std::sort(nodes.begin(), nodes.end());
    return nodes;
}

static bool node_name_matches(const std::string &path, const char *want,
                              std::string *got_out) {
    int fd = open(path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        LOGW("verify: open %s failed: %s (errno=%d)", path.c_str(), strerror(errno), errno);
        return false;
    }
    char got[128] = {0};
    bool ok = ioctl(fd, EVIOCGNAME(sizeof(got) - 1), got) >= 0;
    int err = errno;
    close(fd);
    if (!ok) {
        LOGW("verify: EVIOCGNAME(%s) failed: %s (errno=%d)", path.c_str(),
             strerror(err), err);
        return false;
    }
    if (got_out) *got_out = got;
    return strcmp(got, want) == 0;
}

static InputWatch begin_input_watch(void) {
    InputWatch w;
    w.before = list_input_nodes();
    LOGI("verify: /dev/input has %zu event node(s) before UI_DEV_CREATE", w.before.size());
    for (const auto &n : w.before) LOGD("verify: pre-existing /dev/input/%s", n.c_str());

    w.ifd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
    if (w.ifd >= 0) {
        if (inotify_add_watch(w.ifd, "/dev/input", IN_CREATE | IN_MOVED_TO) >= 0) {
            w.inotify_ok = true;
            LOGI("verify: inotify watch active on /dev/input");
        } else {
            LOGW("verify: inotify_add_watch(/dev/input) failed: %s (errno=%d)",
                 strerror(errno), errno);
            close(w.ifd);
            w.ifd = -1;
        }
    } else {
        LOGW("verify: inotify_init1 failed: %s (errno=%d) - using scan only",
             strerror(errno), errno);
    }
    return w;
}

static void close_input_watch(InputWatch *w) {
    if (w->ifd >= 0) close(w->ifd);
    w->ifd = -1;
}

/*
 * Verifies a freshly created uinput device WITHOUT reading
 * /proc/bus/input/devices (unreadable from shell on some Android 12 devices).
 * Tries three independent ways, logging each one:
 *   (a) UI_GET_SYSNAME -> /sys/devices/virtual/input/<sysname> (or
 *       /sys/class/input/<sysname>) exists,
 *   (b) /dev/input snapshot taken BEFORE UI_DEV_CREATE, diffed afterwards
 *       (plus inotify create events when available),
 *   (c) open the new node and require EVIOCGNAME == virtual device name.
 * One successful way is enough; otherwise the caller destroys the device and
 * never grabs the real touchscreen.
 */
static void verify_virtual_device(int uifd, const char *name, int timeout_ms,
                                  InputWatch *watch, VirtualVerify *result) {
    VirtualVerify v;
    const std::vector<std::string> &before = watch->before;
    int ifd = watch->ifd;
    v.inotify_used = watch->inotify_ok;

    bool sysname_fail_logged = false;
    bool sysfs_fail_logged = false;
    char sysname[96] = {0};
    int waited = 0;

    while (true) {
        // (a) UI_GET_SYSNAME + sysfs existence.
        if (!v.via_sysname) {
            if (!v.sysname_queried) {
                v.sysname_queried = true;
                if (ioctl(uifd, UI_GET_SYSNAME(sizeof(sysname) - 1), sysname) < 0) {
                    LOGW("verify: (a) UI_GET_SYSNAME failed: %s (errno=%d)",
                         strerror(errno), errno);
                } else {
                    v.sysname = sysname;
                    LOGI("verify: (a) UI_GET_SYSNAME -> \"%s\"", sysname);
                }
            }
            if (!v.sysname.empty()) {
                std::string p1 = "/sys/devices/virtual/input/" + v.sysname;
                std::string p2 = "/sys/class/input/" + v.sysname;
                if (path_exists(p1) || path_exists(p2)) {
                    v.via_sysname = true;
                    LOGI("verify: (a) OK - %s exists",
                         path_exists(p1) ? p1.c_str() : p2.c_str());
                } else if (!sysfs_fail_logged) {
                    sysfs_fail_logged = true;
                    LOGW("verify: (a) sysfs %s not present yet", p1.c_str());
                }
            } else if (!sysname_fail_logged) {
                sysname_fail_logged = true;
            }
        }

        // (b) inotify create events, then (c) EVIOCGNAME match.
        if (ifd >= 0 && !v.via_new_node) {
            char buf[4096] __attribute__((aligned(__alignof__(struct inotify_event))));
            ssize_t n;
            while ((n = read(ifd, buf, sizeof(buf))) > 0) {
                for (char *p = buf; p < buf + n;) {
                    struct inotify_event *ev = reinterpret_cast<struct inotify_event *>(p);
                    if (ev->len > 0 && (ev->mask & (IN_CREATE | IN_MOVED_TO))) {
                        std::string entry(ev->name);
                        if (entry.rfind("event", 0) == 0) {
                            std::string node = "/dev/input/" + entry;
                            std::string got;
                            LOGI("verify: (b) inotify IN_CREATE %s", node.c_str());
                            if (node_name_matches(node, name, &got)) {
                                v.via_new_node = true;
                                v.node = node;
                                LOGI("verify: (b) OK via inotify - %s EVIOCGNAME=\"%s\"",
                                     node.c_str(), got.c_str());
                                break;
                            }
                            LOGW("verify: (c) %s EVIOCGNAME=\"%s\" != \"%s\"",
                                 node.c_str(), got.c_str(), name);
                        }
                    }
                    p += sizeof(struct inotify_event) + ev->len;
                }
                if (v.via_new_node) break;
            }
        }

        // (b) plain before/after diff (works even when inotify is unavailable).
        if (!v.via_new_node) {
            std::vector<std::string> now = list_input_nodes();
            for (const auto &entry : now) {
                if (std::find(before.begin(), before.end(), entry) != before.end()) continue;
                std::string node = "/dev/input/" + entry;
                std::string got;
                LOGI("verify: (b) new node %s appeared", node.c_str());
                if (node_name_matches(node, name, &got)) {
                    v.via_new_node = true;
                    v.node = node;
                    LOGI("verify: (b) OK via scan - %s EVIOCGNAME=\"%s\"",
                         node.c_str(), got.c_str());
                } else {
                    LOGW("verify: (c) %s EVIOCGNAME=\"%s\" != \"%s\"",
                         node.c_str(), got.c_str(), name);
                }
            }
        }

        if (v.via_sysname || v.via_new_node) break;
        if (waited >= timeout_ms) break;
        usleep(50 * 1000);
        waited += 50;
    }

    close_input_watch(watch);

    v.ok = v.via_sysname || v.via_new_node;
    if (!v.ok) {
        LOGW("verify: FAILED after %dms - (a) %s, (b) inotify %s, (c) no node with "
             "EVIOCGNAME \"%s\"",
             timeout_ms,
             v.sysname.empty() ? "UI_GET_SYSNAME unavailable"
                               : "sysfs path not found",
             v.inotify_used ? "watched" : "unavailable", name);
    }
    if (result) *result = v;
}

static const char *abs_code_name(int code) {
    switch (code) {
        case ABS_MT_SLOT: return "ABS_MT_SLOT";
        case ABS_MT_TOUCH_MAJOR: return "ABS_MT_TOUCH_MAJOR";
        case ABS_MT_TOUCH_MINOR: return "ABS_MT_TOUCH_MINOR";
        case ABS_MT_WIDTH_MAJOR: return "ABS_MT_WIDTH_MAJOR";
        case ABS_MT_WIDTH_MINOR: return "ABS_MT_WIDTH_MINOR";
        case ABS_MT_ORIENTATION: return "ABS_MT_ORIENTATION";
        case ABS_MT_POSITION_X: return "ABS_MT_POSITION_X";
        case ABS_MT_POSITION_Y: return "ABS_MT_POSITION_Y";
        case ABS_MT_TOOL_TYPE: return "ABS_MT_TOOL_TYPE";
        case ABS_MT_BLOB_ID: return "ABS_MT_BLOB_ID";
        case ABS_MT_TRACKING_ID: return "ABS_MT_TRACKING_ID";
        case ABS_MT_PRESSURE: return "ABS_MT_PRESSURE";
        case ABS_MT_DISTANCE: return "ABS_MT_DISTANCE";
        case ABS_MT_TOOL_X: return "ABS_MT_TOOL_X";
        case ABS_MT_TOOL_Y: return "ABS_MT_TOOL_Y";
        case ABS_X: return "ABS_X";
        case ABS_Y: return "ABS_Y";
        case ABS_PRESSURE: return "ABS_PRESSURE";
        case ABS_TOOL_WIDTH: return "ABS_TOOL_WIDTH";
        default: return "ABS_OTHER";
    }
}

/* Fills every abs axis of the legacy uinput_user_dev from the real device. */
static void fill_legacy_abs(struct uinput_user_dev *udev, const TouchCandidate *src,
                            const std::vector<unsigned long> &abs_bits,
                            int min_x, int max_x, int min_y, int max_y) {
    int copied = 0;
    for (int code = 0; code <= ABS_MAX; ++code) {
        if (!bit_test(abs_bits, code)) continue;

        input_absinfo ai = {};
        bool have = false;
        if (src && src->fd >= 0) {
            have = ioctl(src->fd, EVIOCGABS(code), &ai) == 0;
        }
        if (!have) {
            ai.minimum = 0;
            ai.maximum = 4095;
            if (code == ABS_MT_SLOT) ai.maximum = 9;
            if (code == ABS_MT_TRACKING_ID) ai.maximum = 65535;
            if (code == ABS_MT_PRESSURE || code == ABS_MT_TOUCH_MAJOR) ai.maximum = 255;
            if (code == ABS_MT_POSITION_X) {
                ai.minimum = min_x;
                ai.maximum = max_x;
            }
            if (code == ABS_MT_POSITION_Y) {
                ai.minimum = min_y;
                ai.maximum = max_y;
            }
            LOGW("legacy abs code=%d (%s): EVIOCGABS unavailable, using default "
                 "min=%d max=%d fuzz=%d flat=%d",
                 code, abs_code_name(code), ai.minimum, ai.maximum, ai.fuzz, ai.flat);
        }

        udev->absmin[code] = ai.minimum;
        udev->absmax[code] = ai.maximum;
        udev->absfuzz[code] = ai.fuzz;
        udev->absflat[code] = ai.flat;
        copied++;
        LOGI("legacy abs copied: code=%d (%s) min=%d max=%d fuzz=%d flat=%d res=%d",
             code, abs_code_name(code), ai.minimum, ai.maximum, ai.fuzz, ai.flat,
             ai.resolution);
    }
    LOGI("legacy uinput_user_dev: copied %d abs axes from %s", copied,
         src ? "real device" : "built-in defaults");
}

/*
 * Creates a uinput device. src may be null (used by diagnostics) in which case a
 * minimal MT-B device capability set is used. Returns fd or -1.
 */
static int create_virtual_device(const TouchCandidate *src, const char *name,
                                 int *fail_code) {
    if (fail_code) *fail_code = SENSI_OK;

    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        int e = errno;
        if (fail_code) *fail_code = SENSI_ERR_UINPUT_OPEN;
        set_error(SENSI_ERR_UINPUT_OPEN,
                  std::string("open /dev/uinput failed: ") + strerror(e) +
                  " (Permission denied usually means SELinux/shell policy) - errno=" +
                  std::to_string(e));
        return -1;
    }

    auto set_bit = [&](unsigned long req, int bit) -> bool {
        if (ioctl(fd, req, bit) < 0) {
            LOGW("uinput bit req=0x%lx bit=%d failed: %s", req, bit, strerror(errno));
            return false;
        }
        return true;
    };

    set_bit(UI_SET_EVBIT, EV_SYN);
    set_bit(UI_SET_EVBIT, EV_KEY);
    set_bit(UI_SET_EVBIT, EV_ABS);

    // Android classifies a uinput device that has ABS_MT_POSITION_X/Y but no
    // INPUT_PROP_DIRECT as a pointer (touchpad): injected touches then move a
    // mouse cursor instead of being delivered to the app as touches. Marking
    // the device direct is what makes it a real touchscreen. Without this the
    // whole engine silently degrades into a virtual mouse, so a failure here
    // must abort before anything is grabbed.
    errno = 0;
    if (ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT) < 0) {
        int e = errno;
        if (fail_code) *fail_code = SENSI_ERR_UINPUT_SETUP;
        set_error(SENSI_ERR_UINPUT_SETUP,
                  std::string("UI_SET_PROPBIT(INPUT_PROP_DIRECT) failed: ") +
                  strerror(e) + " (errno=" + std::to_string(e) +
                  ") - without it Android would treat the virtual device as a "
                  "mouse/touchpad, so no grab was attempted");
        close(fd);
        return -1;
    }
    LOGI("uinput property INPUT_PROP_DIRECT set (device will be a touchscreen, not a mouse)");

    int min_x = 0, max_x = 4095, min_y = 0, max_y = 4095;
    std::vector<unsigned long> abs_bits;
    std::vector<unsigned long> key_bits;

    if (src) {
        min_x = src->min_x;
        max_x = src->max_x;
        min_y = src->min_y;
        max_y = src->max_y;
        abs_bits = src->abs_bits;
        key_bits = src->key_bits;
    } else {
        abs_bits.assign(bitmap_words(ABS_MAX), 0);
        auto mark = [&](int code) {
            abs_bits[(size_t) code / (8 * sizeof(unsigned long))] |=
                    1UL << (code % (8 * sizeof(unsigned long)));
        };
        mark(ABS_MT_SLOT);
        mark(ABS_MT_TRACKING_ID);
        mark(ABS_MT_POSITION_X);
        mark(ABS_MT_POSITION_Y);
        mark(ABS_MT_PRESSURE);
        mark(ABS_MT_TOUCH_MAJOR);
        key_bits.assign(bitmap_words(KEY_MAX), 0);
        key_bits[BTN_TOUCH / (8 * sizeof(unsigned long))] |=
                1UL << (BTN_TOUCH % (8 * sizeof(unsigned long)));
    }

    bool use_legacy_dev = false;
    for (int code = 0; code <= ABS_MAX; ++code) {
        if (!bit_test(abs_bits, code)) continue;
        input_absinfo ai = {};
        bool have_ai = false;
        if (src) {
            have_ai = ioctl(src->fd, EVIOCGABS(code), &ai) == 0;
        }
        if (!have_ai) {
            ai.minimum = 0;
            ai.maximum = 4095;
            if (code == ABS_MT_SLOT) ai.maximum = 9;
            if (code == ABS_MT_TRACKING_ID) ai.maximum = 65535;
            if (code == ABS_MT_PRESSURE || code == ABS_MT_TOUCH_MAJOR) ai.maximum = 255;
            if (code == ABS_MT_POSITION_X) {
                ai.minimum = min_x;
                ai.maximum = max_x;
            }
            if (code == ABS_MT_POSITION_Y) {
                ai.minimum = min_y;
                ai.maximum = max_y;
            }
        }
        struct uinput_abs_setup setup = {};
        setup.code = (uint16_t) code;
        setup.absinfo = ai;
        if (ioctl(fd, UI_ABS_SETUP, &setup) < 0) {
            // Very old kernels lack UI_ABS_SETUP; fall back to UI_SET_ABSBIT.
            LOGW("UI_ABS_SETUP code=%d failed: %s (falling back to legacy uinput_user_dev)",
                 code, strerror(errno));
            use_legacy_dev = true;
            ioctl(fd, UI_SET_ABSBIT, code);
        }
    }
    if (use_legacy_dev) {
        LOGW("legacy uinput API fallback selected; abs ranges come from uinput_user_dev");
    }

    for (int code = 0; code <= KEY_MAX; ++code) {
        if (!bit_test(key_bits, code)) continue;
        ioctl(fd, UI_SET_KEYBIT, code);
    }
    // Always allow BTN_TOUCH so touch-up can be forwarded even on odd devices.
    ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH);

    struct uinput_setup usetup = {};
    usetup.id.bustype = src ? src->id.bustype : BUS_VIRTUAL;
    usetup.id.vendor = src ? src->id.vendor : 0x1209;
    usetup.id.product = src ? src->id.product : 0x5345;
    usetup.id.version = src ? src->id.version : 1;
    snprintf(usetup.name, sizeof(usetup.name), "%s", name);

    if (use_legacy_dev) {
        struct uinput_user_dev udev = {};
        snprintf(udev.name, sizeof(udev.name), "%s", name);
        udev.id = usetup.id;
        // Copy min/max/fuzz/flat for EVERY abs axis (slot, tracking id, pressure,
        // touch major, ...), not just X/Y.
        fill_legacy_abs(&udev, src, abs_bits, min_x, max_x, min_y, max_y);
        ssize_t w = write(fd, &udev, sizeof(udev));
        if (w != (ssize_t) sizeof(udev)) {
            int e = errno;
            if (fail_code) *fail_code = SENSI_ERR_UINPUT_SETUP;
            set_error(SENSI_ERR_UINPUT_SETUP,
                      std::string("legacy uinput_user_dev write failed: ") + strerror(e) +
                      " - errno=" + std::to_string(e));
            close(fd);
            return -1;
        }
    } else if (ioctl(fd, UI_DEV_SETUP, &usetup) < 0) {
        int e = errno;
        if (fail_code) *fail_code = SENSI_ERR_UINPUT_SETUP;
        set_error(SENSI_ERR_UINPUT_SETUP,
                  std::string("UI_DEV_SETUP failed: ") + strerror(e) +
                  " - errno=" + std::to_string(e));
        close(fd);
        return -1;
    }
    // Snapshot /dev/input + start inotify BEFORE the device exists, so the new
    // node can be detected by diffing afterwards.
    InputWatch watch = begin_input_watch();
    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        int e = errno;
        close_input_watch(&watch);
        if (fail_code) *fail_code = SENSI_ERR_UINPUT_CREATE;
        set_error(SENSI_ERR_UINPUT_CREATE,
                  std::string("UI_DEV_CREATE failed: ") + strerror(e) +
                  " - errno=" + std::to_string(e));
        close(fd);
        return -1;
    }

    LOGI("uinput device \"%s\" created, verifying node (proc-independent)...", name);
    VirtualVerify vr;
    verify_virtual_device(fd, name, 1000, &watch, &vr);
    if (!vr.ok) {
        if (fail_code) *fail_code = SENSI_ERR_UINPUT_NODE_MISSING;
        set_error(SENSI_ERR_UINPUT_NODE_MISSING,
                  std::string("virtual device \"") + name +
                  "\" could not be verified (UI_GET_SYSNAME sysfs, /dev/input diff and "
                  "EVIOCGNAME all failed); refusing to grab");
        ioctl(fd, UI_DEV_DESTROY);
        close(fd);
        return -1;
    }
    std::string how;
    if (vr.via_sysname) how = "UI_GET_SYSNAME sysfs path";
    if (vr.via_new_node) {
        if (!how.empty()) how += " + ";
        how += "new /dev/input node with matching EVIOCGNAME";
    }
    LOGI("virtual device \"%s\" verified via %s (sysname=\"%s\", node=\"%s\")",
         name, how.c_str(), vr.sysname.c_str(), vr.node.c_str());
    return fd;
}

// ---------------------------------------------------------------------------
// Emergency key (volume up + down) discovery
// ---------------------------------------------------------------------------

static void close_key_devices() {
    int count = g_key_fd_count.load();
    for (int i = 0; i < MAX_EMERGENCY_KEYS; ++i) {
        int fd = g_key_fds[i].exchange(-1);
        if (i < count && fd >= 0) close(fd);
    }
    g_key_fd_count.store(0);
}

static int open_emergency_key_devices() {
    int opened = 0;
    for (int i = 0; i < 32 && opened < MAX_EMERGENCY_KEYS; ++i) {
        char path[64];
        snprintf(path, sizeof(path), "/dev/input/event%d", i);
        int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) continue;

        std::vector<unsigned long> key_bits;
        if (!query_bits(fd, EVIOCGBIT(EV_KEY, bitmap_words(KEY_MAX)), KEY_MAX, &key_bits) ||
            !bit_test(key_bits, KEY_VOLUMEUP) || !bit_test(key_bits, KEY_VOLUMEDOWN)) {
            close(fd);
            continue;
        }
        g_key_fds[opened].store(fd);
        opened++;
        LOGI("emergency key device opened (read-only, not grabbed): %s", path);
    }
    g_key_fd_count.store(opened);
    if (opened == 0) {
        LOGW("no volume-key device found; hardware escape unavailable (other escapes remain)");
    }
    return opened;
}

// ---------------------------------------------------------------------------
// Safety release paths
// ---------------------------------------------------------------------------

static void send_all_fingers_up_locked();

/*
 * Immediately returns touches to the hardware: EVIOCGRAB=0 + UI_DEV_DESTROY.
 * Never closes the source fd here (the reader loop owns it), so no fd-reuse race.
 */
static void safety_release(const char *reason) {
    std::lock_guard<std::mutex> lock(g_release_mutex);
    if (g_grab_active.exchange(false)) {
        int src = g_src_fd.load();
        if (src >= 0) {
            send_all_fingers_up_locked();
            if (ioctl(src, EVIOCGRAB, 0) < 0) {
                LOGE("EVIOCGRAB=0 failed: %s (errno=%d)", strerror(errno), errno);
            } else {
                LOGW("EVIOCGRAB released, reason: %s", reason);
            }
        }
        g_sig_src_fd = -1;
    }
    int ui = g_ui_fd.load();
    if (ui >= 0 && !g_ui_destroyed.exchange(true)) {
        if (ioctl(ui, UI_DEV_DESTROY) < 0) {
            LOGE("UI_DEV_DESTROY failed: %s (errno=%d)", strerror(errno), errno);
        } else {
            LOGI("virtual device destroyed, reason: %s", reason);
        }
        g_sig_ui_fd = -1;
    }
    g_stop.store(true);
    g_running.store(false);
    if (g_state.load() == STATE_ACTIVE) g_state.store(STATE_IDLE);
}

static void close_all_fds() {
    std::lock_guard<std::mutex> lock(g_release_mutex);
    int ui = g_ui_fd.exchange(-1);
    if (ui >= 0) {
        if (!g_ui_destroyed.exchange(true)) ioctl(ui, UI_DEV_DESTROY);
        close(ui);
    }
    int src = g_src_fd.exchange(-1);
    if (src >= 0) close(src);
    g_sig_src_fd = -1;
    g_sig_ui_fd = -1;
}

static void install_signal_handlers() {
    static std::once_flag once;
    std::call_once(once, [] {
        struct sigaction sa = {};
        sa.sa_flags = SA_RESETHAND | SA_NODEFER;
        sa.sa_handler = [](int sig) {
            // async-signal-safe only: ioctl/close/_exit
            if (g_sig_src_fd >= 0) {
                ioctl((int) g_sig_src_fd, EVIOCGRAB, 0);
            }
            if (g_sig_ui_fd >= 0) {
                close((int) g_sig_ui_fd);
            }
            _exit(128 + sig);
        };
        sigaction(SIGSEGV, &sa, nullptr);
        sigaction(SIGABRT, &sa, nullptr);
        sigaction(SIGBUS, &sa, nullptr);
        sigaction(SIGFPE, &sa, nullptr);
        LOGI("fatal-signal handlers installed (SEGV/ABRT/BUS/FPE)");
    });
}

// ---------------------------------------------------------------------------
// Slot scaling logic
// ---------------------------------------------------------------------------

struct SlotState {
    bool active = false;
    bool pending_init_x = true;
    bool pending_init_y = true;
    int last_raw_x = 0;
    int last_raw_y = 0;
    float virt_x = 0.0f;
    float virt_y = 0.0f;
    int tracking_id = -1;
};

static SlotState g_slots[MAX_SLOTS];
static int g_current_slot = 0;
static bool g_btn_touch_down = false;

static inline int clamp_int(int v, int lo, int hi) {
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
}

static void reset_slot(int slot) {
    if (slot < 0 || slot >= MAX_SLOTS) return;
    g_slots[slot] = SlotState();
}

static void reset_all_slots() {
    for (int i = 0; i < MAX_SLOTS; ++i) reset_slot(i);
    g_current_slot = 0;
    g_btn_touch_down = false;
}

static void send_all_fingers_up_locked() {
    int ui = g_ui_fd.load();
    if (ui < 0 || g_ui_destroyed.load()) return;
    struct input_event ev[3 * MAX_SLOTS + 1];
    size_t n = 0;
    for (int slot = 0; slot < MAX_SLOTS; ++slot) {
        if (!g_slots[slot].active) continue;
        ev[n].time = {0, 0};
        ev[n].type = EV_ABS;
        ev[n].code = ABS_MT_SLOT;
        ev[n].value = slot;
        n++;
        ev[n].time = {0, 0};
        ev[n].type = EV_ABS;
        ev[n].code = ABS_MT_TRACKING_ID;
        ev[n].value = -1;
        n++;
        reset_slot(slot);
    }
    ev[n].time = {0, 0};
    ev[n].type = EV_SYN;
    ev[n].code = SYN_REPORT;
    ev[n].value = 0;
    n++;
    ssize_t rc = write(ui, ev, n * sizeof(struct input_event));
    if (rc < 0) {
        LOGW("release-all write failed: %s (errno=%d)", strerror(errno), errno);
    }
}

// ---------------------------------------------------------------------------
// Reader thread
// ---------------------------------------------------------------------------

static void set_reader_priority() {
    struct sched_param sp = {};
    sp.sched_priority = 2; // SCHED_FIFO minimum useful priority
    if (sched_setscheduler(0, SCHED_FIFO, &sp) < 0) {
        LOGW("sched_setscheduler(SCHED_FIFO) failed: %s (errno=%d) - continuing normally",
             strerror(errno), errno);
    } else {
        LOGI("reader thread priority raised to SCHED_FIFO(2)");
    }
}

static void push_scaled_event(struct input_event *out, struct input_event *ev,
                              const char *reason_tag) {
    // Copy timestamp/layout, modify only the value.
    (void) reason_tag;
    out->time = ev->time;
    out->type = ev->type;
    out->code = ev->code;
    out->value = ev->value;
}

static void flush_packet(const std::vector<struct input_event> &packet,
                         int64_t read_ts_us) {
    int ui = g_ui_fd.load();
    if (ui < 0 || g_ui_destroyed.load()) return;

    size_t bytes = packet.size() * sizeof(struct input_event);
    ssize_t rc = 0;
    int attempts = 0;
    do {
        rc = write(ui, packet.data(), bytes);
    } while (rc < 0 && errno == EINTR && ++attempts < 3);

    if (rc < 0) {
        int e = errno;
        if (e == EAGAIN || e == EWOULDBLOCK) {
            uint64_t n = ++g_eagain;
            ++g_packets_dropped;
            if ((n % 100) == 1) {
                LOGW("uinput write EAGAIN (epoll full?) - packet dropped, count=%llu",
                     (unsigned long long) n);
            }
            return;
        }
        ++g_packets_dropped;
        LOGE("uinput write failed: %s (errno=%d) - packet dropped", strerror(e), e);
        return;
    }
    g_ev_written.fetch_add(packet.size());
    g_packets_written.fetch_add(1);
    int64_t latency = now_us() - read_ts_us;
    if (latency < 0) latency = 0;
    g_latency_sum_us.fetch_add((uint64_t) latency);
    uint64_t prev_max = g_latency_max_us.load();
    while (latency > (int64_t) prev_max &&
           !g_latency_max_us.compare_exchange_weak(prev_max, (uint64_t) latency)) {
    }
}

static void handle_abs_event(struct input_event *ev) {
    int slot = g_current_slot;
    bool valid = slot >= 0 && slot < MAX_SLOTS;

    switch (ev->code) {
        case ABS_MT_SLOT: {
            if (ev->value < 0 || ev->value >= MAX_SLOTS) {
                LOGW("out-of-range ABS_MT_SLOT %d (max %d), ignoring slot tracking",
                     ev->value, MAX_SLOTS - 1);
                g_current_slot = -1;
            } else {
                g_current_slot = ev->value;
            }
            return;
        }
        case ABS_MT_TRACKING_ID: {
            if (!valid) return;
            SlotState &s = g_slots[slot];
            if (ev->value < 0) {
                s.active = false;
                s.pending_init_x = true;
                s.pending_init_y = true;
                s.tracking_id = -1;
            } else {
                s.active = true;
                s.tracking_id = ev->value;
                s.pending_init_x = true;
                s.pending_init_y = true;
            }
            return;
        }
        case ABS_MT_POSITION_X: {
            if (!valid) return;
            SlotState &s = g_slots[slot];
            int raw = ev->value;
            if (!s.active) return; // no active finger: forward unchanged
            if (s.pending_init_x) {
                s.virt_x = (float) clamp_int(raw, g_abs_min_x, g_abs_max_x);
                s.pending_init_x = false;
            } else {
                float k = g_kx.load(std::memory_order_relaxed);
                s.virt_x += (float) (raw - s.last_raw_x) * k;
                s.virt_x = std::fmaxf((float) g_abs_min_x,
                                      std::fminf((float) g_abs_max_x, s.virt_x));
            }
            s.last_raw_x = raw;
            ev->value = (int) lroundf(s.virt_x);
            return;
        }
        case ABS_MT_POSITION_Y: {
            if (!valid) return;
            SlotState &s = g_slots[slot];
            int raw = ev->value;
            if (!s.active) return;
            if (s.pending_init_y) {
                s.virt_y = (float) clamp_int(raw, g_abs_min_y, g_abs_max_y);
                s.pending_init_y = false;
            } else {
                float k = g_ky.load(std::memory_order_relaxed);
                s.virt_y += (float) (raw - s.last_raw_y) * k;
                s.virt_y = std::fmaxf((float) g_abs_min_y,
                                      std::fminf((float) g_abs_max_y, s.virt_y));
            }
            s.last_raw_y = raw;
            ev->value = (int) lroundf(s.virt_y);
            return;
        }
        default:
            return;
    }
}

static void reader_loop() {
    pthread_setname_np(pthread_self(), "sensi-reader");
    set_reader_priority();
    LOGI("reader thread started");

    int src = g_src_fd.load();
    if (src < 0) {
        LOGE("reader started without source fd");
        g_running.store(false);
        g_stop.store(true);
        return;
    }

    std::vector<struct input_event> read_buf(READ_BUF_EVENTS);
    std::vector<struct input_event> packet;
    packet.reserve(PACKET_MAX_EVENTS);

    uint64_t window_events = 0;
    int64_t window_start = now_ms();

    while (!g_stop.load()) {
        struct pollfd pfd = {};
        pfd.fd = src;
        pfd.events = POLLIN;
        int pr = poll(&pfd, 1, 100);
        g_last_loop_ms.store(now_ms());

        if (pr < 0) {
            if (errno == EINTR) continue;
            LOGE("poll(source) failed: %s (errno=%d)", strerror(errno), errno);
            break;
        }
        if (pr == 0) {
            // idle tick; still refresh stall watchdog and stats window
        } else if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) {
            LOGE("poll(source) revents=0x%x - device gone", pfd.revents);
            break;
        } else if (pfd.revents & POLLIN) {
            int64_t read_ts = now_us();
            ssize_t n = read(src, read_buf.data(),
                             read_buf.size() * sizeof(struct input_event));
            if (n < 0) {
                if (errno == EAGAIN || errno == EINTR) {
                    continue;
                }
                LOGE("read(source) failed: %s (errno=%d) - stopping", strerror(errno), errno);
                break;
            }
            size_t count = (size_t) n / sizeof(struct input_event);
            g_ev_read.fetch_add(count);
            window_events += count;

            for (size_t i = 0; i < count; ++i) {
                struct input_event ev = read_buf[i];

                if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
                    struct input_event syn = ev;
                    packet.push_back(syn);
                    flush_packet(packet, read_ts);
                    packet.clear();
                    continue;
                }

                // The virtual device only carries the EV/KEY/ABS bits copied from the
                // real device. Forwarding EV_MSC/EV_SW/... would make the whole write
                // fail with EINVAL and lose the touch, so drop unsupported types.
                if (ev.type != EV_SYN && ev.type != EV_KEY && ev.type != EV_ABS) {
                    continue;
                }

                if (ev.type == EV_ABS) {
                    handle_abs_event(&ev);
                } else if (ev.type == EV_KEY && ev.code == BTN_TOUCH) {
                    g_btn_touch_down = ev.value != 0;
                    if (!g_btn_touch_down) {
                        // Defensive: a touch-up ends every slot even if tracking ids
                        // were never delivered.
                        for (int s = 0; s < MAX_SLOTS; ++s) {
                            if (g_slots[s].active) {
                                g_slots[s].active = false;
                                g_slots[s].pending_init_x = true;
                                g_slots[s].pending_init_y = true;
                            }
                        }
                    }
                }

                struct input_event out = {};
                push_scaled_event(&out, &ev, "forward");
                packet.push_back(out);

                if (packet.size() >= PACKET_MAX_EVENTS) {
                    LOGE("packet exceeded %d events without SYN_REPORT - dropped",
                         PACKET_MAX_EVENTS);
                    ++g_packets_dropped;
                    packet.clear();
                }
            }
        }

        int64_t now = now_ms();
        if (now - window_start >= 1000) {
            g_events_per_sec.store((uint64_t) ((window_events * 1000) /
                                               (uint64_t) (now - window_start)));
            window_events = 0;
            window_start = now;
        }
    }

    packet.clear();
    LOGI("reader thread exiting (stop=%d)", g_stop.load() ? 1 : 0);
    reset_all_slots();
    safety_release("reader loop exit");
    close_all_fds();
    close_key_devices();
}

// ---------------------------------------------------------------------------
// Watchdog + heartbeat
// ---------------------------------------------------------------------------

static void watchdog_loop() {
    pthread_setname_np(pthread_self(), "sensi-watchdog");
    LOGI("watchdog thread started (loop stall >1000ms, heartbeat timeout >3000ms)");
    int64_t last_stat_ms = now_ms();
    while (!g_stop.load()) {
        usleep(200 * 1000);
        if (g_stop.load()) break;

        int state = g_state.load();
        if (state != STATE_ACTIVE && state != STATE_READY) continue;

        int64_t now = now_ms();
        int64_t loop_age = now - g_last_loop_ms.load();
        if (loop_age > 1000) {
            LOGE("WATCHDOG: event loop stalled for %lld ms - releasing grab",
                 (long long) loop_age);
            safety_release("watchdog: event loop stalled");
            g_state.store(STATE_ERROR);
            set_error(SENSI_ERR_INTERNAL, "watchdog released grab: event loop stalled");
            break;
        }

        int64_t hb_age = now - g_last_heartbeat_ms.load();
        if (hb_age > 3000) {
            LOGW("WATCHDOG: no heartbeat for %lld ms - releasing grab and going IDLE",
                 (long long) hb_age);
            safety_release("heartbeat timeout");
            g_state.store(STATE_IDLE);
            break;
        }

        if (now - last_stat_ms >= 5000) {
            last_stat_ms = now;
            uint64_t packets = g_packets_written.load();
            uint64_t avg = packets ? g_latency_sum_us.load() / packets : 0;
            LOGI("stats: read=%llu written=%llu packets=%llu dropped=%llu eagain=%llu "
                 "latency avg=%llu us max=%llu us eps=%llu",
                 (unsigned long long) g_ev_read.load(),
                 (unsigned long long) g_ev_written.load(),
                 (unsigned long long) packets,
                 (unsigned long long) g_packets_dropped.load(),
                 (unsigned long long) g_eagain.load(),
                 (unsigned long long) avg,
                 (unsigned long long) g_latency_max_us.load(),
                 (unsigned long long) g_events_per_sec.load());
        }
    }
    LOGI("watchdog thread exiting");
}

static void key_loop() {
    pthread_setname_np(pthread_self(), "sensi-keys");
    if (g_key_fd_count.load() == 0) return;
    LOGI("volume-key escape thread started (hold VOL+ and VOL- for 2s)");

    bool up = false, down = false;
    int64_t both_since = 0;
    bool latched = false;

    while (!g_stop.load()) {
        struct pollfd pfds[MAX_EMERGENCY_KEYS] = {};
        int nfds = g_key_fd_count.load();
        for (int i = 0; i < nfds; ++i) {
            pfds[i].fd = g_key_fds[i].load();
            pfds[i].events = POLLIN;
        }
        struct pollfd extra = {};
        extra.fd = -1;
        (void) extra;

        int pr = poll(pfds, (nfds_t) nfds, 200);
        if (pr < 0 && errno != EINTR) {
            LOGE("poll(keys) failed: %s (errno=%d)", strerror(errno), errno);
            break;
        }
        if (pr > 0) {
            for (int i = 0; i < nfds; ++i) {
                if (!(pfds[i].revents & POLLIN)) continue;
                struct input_event evs[16];
                ssize_t n = read(pfds[i].fd, evs, sizeof(evs));
                if (n <= 0) continue;
                size_t count = (size_t) n / sizeof(struct input_event);
                for (size_t k = 0; k < count; ++k) {
                    if (evs[k].type != EV_KEY) continue;
                    if (evs[k].code == KEY_VOLUMEUP) up = evs[k].value != 0;
                    else if (evs[k].code == KEY_VOLUMEDOWN) down = evs[k].value != 0;
                }
            }
        }

        int64_t now = now_ms();
        if (up && down) {
            if (both_since == 0) both_since = now;
            if (!latched && now - both_since >= 2000) {
                latched = true;
                LOGE("HARDWARE ESCAPE: VOL_UP+VOL_DOWN held 2s - releasing grab");
                safety_release("hardware volume keys");
                g_state.store(STATE_IDLE);
                break;
            }
        } else {
            both_since = 0;
            latched = false;
        }
    }
    LOGI("volume-key escape thread exiting");
}

// ---------------------------------------------------------------------------
// Start / stop
// ---------------------------------------------------------------------------

static void join_threads() {
    if (g_reader_thread.joinable()) g_reader_thread.join();
    if (g_watchdog_thread.joinable()) g_watchdog_thread.join();
    if (g_key_thread.joinable()) g_key_thread.join();
}

int sensi_start(void) {
    std::lock_guard<std::mutex> lock(g_op_mutex);
    if (g_state.load() == STATE_ACTIVE) {
        LOGI("start requested while already ACTIVE - no-op");
        return SENSI_OK;
    }

    join_threads();
    close_all_fds();
    close_key_devices();
    g_ui_destroyed.store(false);
    reset_all_slots();
    {
        std::lock_guard<std::mutex> err_lock(g_err_mutex);
        g_last_error.clear();
    }
    g_last_error_code.store(SENSI_OK);

    LOGI("=== start requested ===");
    install_signal_handlers();

    int chosen = -1;
    std::vector<TouchCandidate> candidates = scan_touch_devices(true, &chosen);
    if (chosen < 0) {
        for (auto &c : candidates) if (c.fd >= 0) close(c.fd);
        set_error(SENSI_ERR_NO_TOUCH_DEVICE,
                  "no touchscreen with EV_ABS + ABS_MT_POSITION_X/Y (max>min) found "
                  "in /dev/input/event0..31");
        g_state.store(STATE_ERROR);
        return SENSI_ERR_NO_TOUCH_DEVICE;
    }

    TouchCandidate &src = candidates[(size_t) chosen];
    g_src_fd.store(src.fd);
    g_sig_src_fd = src.fd;
    g_abs_min_x = src.min_x;
    g_abs_max_x = src.max_x;
    g_abs_min_y = src.min_y;
    g_abs_max_y = src.max_y;
    g_max_slots.store(src.max_slots);
    g_has_slot_axis.store(src.has_slot_axis);
    snprintf(g_device_path, sizeof(g_device_path), "%s", src.path.c_str());
    snprintf(g_device_name, sizeof(g_device_name), "%s", src.name.c_str());
    LOGI("using %s \"%s\" x=%d..%d y=%d..%d slots=%d protocol=%s",
         g_device_path, g_device_name, g_abs_min_x, g_abs_max_x,
         g_abs_min_y, g_abs_max_y, src.max_slots,
         src.has_slot_axis ? "MT-B" : "MT-A/single");

    if (!src.has_slot_axis) {
        LOGW("device has no ABS_MT_SLOT: single-slot fallback will be used");
    }

    int fail_code = SENSI_OK;
    int ui = create_virtual_device(&src, VIRT_NAME, &fail_code);
    if (ui < 0) {
        // Safety: never grab when the virtual device could not be created.
        for (auto &c : candidates) if (c.fd >= 0) close(c.fd);
        g_src_fd.store(-1);
        g_sig_src_fd = -1;
        g_state.store(STATE_ERROR);
        LOGW("aborting start: virtual device creation failed - real touchscreen NOT grabbed");
        return fail_code != SENSI_OK ? fail_code : SENSI_ERR_UINPUT_CREATE;
    }
    g_ui_fd.store(ui);
    g_sig_ui_fd = ui;
    g_state.store(STATE_READY);
    LOGI("state READY: virtual device created, touchscreen not grabbed yet");

    // Close non-chosen candidate fds (the chosen one is now g_src_fd).
    for (size_t i = 0; i < candidates.size(); ++i) {
        if ((int) i != chosen && candidates[i].fd >= 0) {
            close(candidates[i].fd);
            candidates[i].fd = -1;
        }
    }
    src.fd = -1; // ownership moved to g_src_fd

    open_emergency_key_devices();

    g_stop.store(false);
    g_running.store(true);
    g_last_heartbeat_ms.store(now_ms());
    g_last_loop_ms.store(now_ms());
    g_started_ms.store(now_ms());
    g_ev_read.store(0);
    g_ev_written.store(0);
    g_packets_written.store(0);
    g_packets_dropped.store(0);
    g_eagain.store(0);
    g_latency_sum_us.store(0);
    g_latency_max_us.store(0);
    g_events_per_sec.store(0);

    g_watchdog_thread = std::thread(watchdog_loop);
    g_key_thread = std::thread(key_loop);
    g_reader_thread = std::thread(reader_loop);

    // Mandatory order: virtual device verified first, grab only now.
    if (ioctl(g_src_fd.load(), EVIOCGRAB, 1) < 0) {
        int e = errno;
        set_error(SENSI_ERR_GRAB,
                  std::string("EVIOCGRAB failed: ") + strerror(e) +
                  " - errno=" + std::to_string(e));
        g_stop.store(true);
        g_running.store(false);
        join_threads();
        close_all_fds();
        close_key_devices();
        g_state.store(STATE_ERROR);
        LOGW("aborting start: grab failed, nothing is grabbed");
        return SENSI_ERR_GRAB;
    }

    g_grab_active.store(true);
    g_state.store(STATE_ACTIVE);
    g_last_loop_ms.store(now_ms());
    LOGI("=== ACTIVE: grabbed %s, virtual %s, factor X=%.2f Y=%.2f ===",
         g_device_path, VIRT_NAME, g_kx.load(), g_ky.load());
    return SENSI_OK;
}

int sensi_stop(const char *reason) {
    std::lock_guard<std::mutex> lock(g_op_mutex);
    LOGI("=== stop requested: %s ===", reason ? reason : "unspecified");
    safety_release(reason ? reason : "stop");
    g_running.store(false);
    g_stop.store(true);
    join_threads();
    close_all_fds();
    close_key_devices();
    reset_all_slots();
    if (g_state.load() != STATE_ERROR) g_state.store(STATE_IDLE);
    LOGI("=== IDLE: everything released ===");
    return SENSI_OK;
}

int sensi_emergency_release(const char *reason) {
    LOGW("=== EMERGENCY RELEASE: %s ===", reason ? reason : "unspecified");
    safety_release(reason ? reason : "emergency");
    return SENSI_OK;
}

int sensi_set_enabled(int enabled) {
    return enabled ? sensi_start() : sensi_stop("disabled by user");
}

int sensi_set_factor(float x, float y) {
    if (!std::isfinite(x) || !std::isfinite(y) || x < 0.5f || x > 2.5f ||
        y < 0.5f || y > 2.5f) {
        LOGE("set_factor rejected: X=%.3f Y=%.3f (valid 0.5..2.5)", x, y);
        return SENSI_ERR_BAD_ARG;
    }
    g_kx.store(x);
    g_ky.store(y);
    LOGI("factor updated: X=%.2f Y=%.2f", x, y);
    return SENSI_OK;
}

int sensi_heartbeat(void) {
    g_last_heartbeat_ms.store(now_ms());
    return SENSI_OK;
}

// ---------------------------------------------------------------------------
// Status + logs JSON
// ---------------------------------------------------------------------------

static const char *state_name(int s) {
    switch (s) {
        case STATE_READY: return "READY";
        case STATE_ACTIVE: return "ACTIVE";
        case STATE_ERROR: return "ERROR";
        default: return "IDLE";
    }
}

char *sensi_get_status_json(void) {
    char buf[4096];
    std::string err;
    {
        std::lock_guard<std::mutex> lock(g_err_mutex);
        err = g_last_error;
    }
    int64_t now = now_ms();
    int64_t hb_age = g_last_heartbeat_ms.load() > 0 ? now - g_last_heartbeat_ms.load() : -1;
    uint64_t packets = g_packets_written.load();
    uint64_t avg = packets ? g_latency_sum_us.load() / packets : 0;

    snprintf(buf, sizeof(buf),
             "{\"state\":\"%s\",\"enabled\":%s,\"grab\":%s,\"factorX\":%.2f,"
             "\"factorY\":%.2f,\"device\":\"%s\",\"deviceName\":\"%s\","
             "\"slots\":%d,\"hasSlotAxis\":%s,\"eventsRead\":%llu,"
             "\"eventsWritten\":%llu,\"eventsPerSec\":%llu,\"latencyAvgUs\":%llu,"
             "\"latencyMaxUs\":%llu,\"packetsDropped\":%llu,\"eagain\":%llu,"
             "\"lastHeartbeatAgoMs\":%lld,\"uptimeMs\":%lld,\"lastErrorCode\":%d,"
             "\"lastError\":\"%s\"}",
             state_name(g_state.load()),
             g_state.load() == STATE_ACTIVE ? "true" : "false",
             g_grab_active.load() ? "true" : "false",
             g_kx.load(), g_ky.load(),
             g_device_path, json_escape(g_device_name).c_str(),
             g_max_slots.load(),
             g_has_slot_axis.load() ? "true" : "false",
             (unsigned long long) g_ev_read.load(),
             (unsigned long long) g_ev_written.load(),
             (unsigned long long) g_events_per_sec.load(),
             (unsigned long long) avg,
             (unsigned long long) g_latency_max_us.load(),
             (unsigned long long) g_packets_dropped.load(),
             (unsigned long long) g_eagain.load(),
             (long long) hb_age,
             (long long) (g_started_ms.load() > 0 ? now - g_started_ms.load() : 0),
             g_last_error_code.load(),
             json_escape(err).c_str());

    char *out = (char *) malloc(strlen(buf) + 1);
    if (!out) return nullptr;
    strcpy(out, buf);
    return out;
}

char *sensi_get_logs_json(int since_seq) {
    std::string out;
    out.reserve(4096);
    out += "[";
    bool truncated = false;

    std::lock_guard<std::mutex> lock(g_log_mutex);
    bool first = true;
    int total = g_log_count;
    int64_t start = g_log_seq - total;
    if (start < 1) start = 1;
    for (int64_t seq = start; seq < g_log_seq; ++seq) {
        const LogEntry &e = g_logs[seq % LOG_RING_CAPACITY];
        if (e.seq != seq) continue;
        if (since_seq > 0 && e.seq <= (int64_t) since_seq) continue;
        // Build the record as a complete string. A fixed-size snprintf here used
        // to cut long messages mid-string and break the whole JSON array.
        std::string record = sensitune::sensi_log_record_json(
                (long long) e.seq, (long long) e.ts_ms, e.level, e.thread, e.msg);
        if (out.size() + record.size() + 2 > 512 * 1024) {
            truncated = true;
            break;
        }
        if (!first) out += ",";
        first = false;
        out += record;
    }
    out += "]";
    // NOTE: never log while holding g_log_mutex (log_message locks it too).
    if (truncated) {
        LOGW("getLogs: response truncated at %zu bytes", out.size());
    }

    char *res = (char *) malloc(out.size() + 1);
    if (!res) return nullptr;
    memcpy(res, out.c_str(), out.size() + 1);
    return res;
}

// ---------------------------------------------------------------------------
// Diagnostics (never grabs)
// ---------------------------------------------------------------------------

static std::string read_selinux() {
    std::ifstream f("/sys/fs/selinux/enforce");
    if (f.is_open()) {
        std::string v;
        std::getline(f, v);
        if (v == "0") return "Permissive";
        if (v == "1") return "Enforcing";
    }
    FILE *p = popen("getenforce 2>/dev/null", "r");
    if (p) {
        char buf[64] = {0};
        if (fgets(buf, sizeof(buf), p)) {
            std::string s(buf);
            while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) s.pop_back();
            pclose(p);
            if (!s.empty()) return s;
        } else {
            pclose(p);
        }
    }
    return "unknown";
}

char *sensi_diagnose_json(void) {
    LOGI("diagnose: starting non-grabbing capability check");

    int chosen = -1;
    std::vector<TouchCandidate> candidates = scan_touch_devices(false, &chosen);

    struct stat st = {};
    bool uinput_exists = stat("/dev/uinput", &st) == 0;
    int open_errno = 0;
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    bool uinput_openable = fd >= 0;
    if (fd < 0) {
        open_errno = errno;
        LOGW("diagnose: /dev/uinput open failed: %s (errno=%d)", strerror(errno), errno);
    }

    int test_fail = SENSI_OK;
    bool test_created = false;
    if (uinput_openable) {
        close(fd);
        const char *diag_name = "SensiTune Diag Device";
        int tfd = create_virtual_device(nullptr, diag_name, &test_fail);
        if (tfd >= 0) {
            test_created = true;
            ioctl(tfd, UI_DEV_DESTROY);
            close(tfd);
            LOGI("diagnose: test virtual device created and destroyed successfully");
        }
    }

    std::string selinux = read_selinux();

    std::string out = "{";
    out += "\"uinputExists\":" + std::string(uinput_exists ? "true" : "false");
    out += ",\"uinputOpenable\":" + std::string(uinput_openable ? "true" : "false");
    out += ",\"uinputErrno\":" + std::to_string(open_errno);
    out += ",\"uinputError\":\"" +
           json_escape(uinput_openable ? "" : strerror(open_errno)) + "\"";
    out += ",\"uinputTestDeviceCreated\":" +
           std::string(test_created ? "true" : "false");
    out += ",\"uinputTestError\":\"" +
           json_escape(test_fail == SENSI_OK ? "" : sensi_strerror(test_fail)) + "\"";
    out += ",\"selinux\":\"" + json_escape(selinux) + "\"";
    out += ",\"selectedDevice\":\"" + json_escape(chosen >= 0 ? candidates[(size_t) chosen].path : "") + "\"";
    out += ",\"touchCandidates\":[";
    for (size_t i = 0; i < candidates.size(); ++i) {
        const TouchCandidate &c = candidates[i];
        if (i) out += ",";
        out += "{\"path\":\"" + json_escape(c.path) + "\",\"name\":\"" +
               json_escape(c.name) + "\",\"minX\":" + std::to_string(c.min_x) +
               ",\"maxX\":" + std::to_string(c.max_x) +
               ",\"minY\":" + std::to_string(c.min_y) +
               ",\"maxY\":" + std::to_string(c.max_y) +
               ",\"resX\":" + std::to_string(c.res_x) +
               ",\"resY\":" + std::to_string(c.res_y) +
               ",\"slots\":" + std::to_string(c.max_slots) +
               ",\"selected\":" + std::string((int) i == chosen ? "true" : "false") + "}";
    }
    out += "],\"engineState\":\"" + std::string(state_name(g_state.load())) + "\"";
    out += ",\"factorX\":" + std::to_string(g_kx.load());
    out += ",\"factorY\":" + std::to_string(g_ky.load());
    out += "}";

    char *res = (char *) malloc(out.size() + 1);
    if (!res) return nullptr;
    memcpy(res, out.c_str(), out.size() + 1);
    return res;
}

// ---------------------------------------------------------------------------
// JNI exports (never throw)
// ---------------------------------------------------------------------------

extern "C" {

JNIEXPORT jint JNICALL
Java_com_example_sensitune_NativeBridge_start(JNIEnv *, jclass) {
    return sensi_start();
}

JNIEXPORT jint JNICALL
Java_com_example_sensitune_NativeBridge_stop(JNIEnv *env, jclass, jstring reason) {
    const char *r = "stop";
    if (reason) r = env->GetStringUTFChars(reason, nullptr);
    std::string copy = r ? r : "stop";
    if (reason && r) env->ReleaseStringUTFChars(reason, r);
    return sensi_stop(copy.c_str());
}

JNIEXPORT jint JNICALL
Java_com_example_sensitune_NativeBridge_setEnabled(JNIEnv *, jclass, jboolean enabled) {
    return sensi_set_enabled(enabled ? 1 : 0);
}

JNIEXPORT jint JNICALL
Java_com_example_sensitune_NativeBridge_setFactor(JNIEnv *, jclass, jfloat x, jfloat y) {
    return sensi_set_factor(x, y);
}

JNIEXPORT jint JNICALL
Java_com_example_sensitune_NativeBridge_heartbeat(JNIEnv *, jclass) {
    return sensi_heartbeat();
}

JNIEXPORT jint JNICALL
Java_com_example_sensitune_NativeBridge_emergencyRelease(JNIEnv *env, jclass,
                                                         jstring reason) {
    const char *r = "emergency";
    if (reason) r = env->GetStringUTFChars(reason, nullptr);
    std::string copy = r ? r : "emergency";
    if (reason && r) env->ReleaseStringUTFChars(reason, r);
    return sensi_emergency_release(copy.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_example_sensitune_NativeBridge_getStatus(JNIEnv *env, jclass) {
    char *json = sensi_get_status_json();
    if (!json) return env->NewStringUTF("{}");
    jstring result = env->NewStringUTF(json);
    free(json);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_example_sensitune_NativeBridge_getLogs(JNIEnv *env, jclass, jint since_seq) {
    char *json = sensi_get_logs_json(since_seq);
    if (!json) return env->NewStringUTF("[]");
    jstring result = env->NewStringUTF(json);
    free(json);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_example_sensitune_NativeBridge_diagnose(JNIEnv *env, jclass) {
    char *json = sensi_diagnose_json();
    if (!json) return env->NewStringUTF("{}");
    jstring result = env->NewStringUTF(json);
    free(json);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_example_sensitune_NativeBridge_nativeVersion(JNIEnv *, jclass) {
    return 1;
}

} // extern "C"
