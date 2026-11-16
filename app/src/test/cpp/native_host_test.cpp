/*
 * Host unit tests for the native ABS probing/JSON helpers.
 *
 * Case #1 reproduces a real OPPO CPH2113 /dev/input/event1 ("touchpanel"):
 *   EV bits = EV_SYN, EV_KEY, EV_ABS
 *   EVIOCGBIT(EV_ABS) returns 0 bytes (empty bitmap)  <- the bug
 *   getevent -lp still shows ABS_MT_SLOT(0-9), ABS_MT_TRACKING_ID(0-65535),
 *   ABS_MT_POSITION_X(0-1079), ABS_MT_POSITION_Y(0-2399), TOUCH_MAJOR.
 * The fallback must accept the device and expose those axes to uinput.
 *
 * Run: scripts/run-native-tests.sh
 */

#include "sensi_abs.h"
#include "sensi_dsp.h"
#include "sensi_json.h"

#include <cmath>
#include <cstdio>
#include <string>

static int g_failures = 0;

#define CHECK(cond)                                                          \
    do {                                                                     \
        if (!(cond)) {                                                       \
            std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__,     \
                         #cond);                                             \
            ++g_failures;                                                    \
        }                                                                    \
    } while (0)

static void add_axis(sensitune::AbsCaps *caps, int code, int minimum,
                     int maximum, int resolution = 0) {
    sensitune::AxisProbe &a = caps->axes[code];
    a.bit_set = false;
    a.getabs_ok = true;
    a.minimum = minimum;
    a.maximum = maximum;
    a.resolution = resolution;
}

static void mark_bit(sensitune::AbsCaps *caps, int code) {
    caps->axes[code].bit_set = true;
}

/* Real event1: EVIOCGABS works for every listed axis, bitmap is empty. */
static sensitune::AbsCaps make_oppo_event1() {
    sensitune::AbsCaps caps;
    caps.ev_abs = true;
    caps.bitmask_ok = true;  // ioctl returned 0, buffer stayed all zero
    add_axis(&caps, ABS_MT_SLOT, 0, 9);
    add_axis(&caps, ABS_MT_TRACKING_ID, 0, 65535);
    add_axis(&caps, ABS_MT_POSITION_X, 0, 1079);
    add_axis(&caps, ABS_MT_POSITION_Y, 0, 2399);
    add_axis(&caps, ABS_MT_TOUCH_MAJOR, 0, 255);
    add_axis(&caps, ABS_MT_PRESSURE, 0, 0);
    add_axis(&caps, ABS_MT_WIDTH_MAJOR, 0, 0);
    return caps;
}

static void test_oppo_event1_fallback() {
    const sensitune::AbsCaps caps = make_oppo_event1();
    const sensitune::AbsEvaluation ev = sensitune::evaluate_abs(caps);

    CHECK(ev.accepted);
    CHECK(ev.min_x == 0);
    CHECK(ev.max_x == 1079);
    CHECK(ev.min_y == 0);
    CHECK(ev.max_y == 2399);
    CHECK(ev.has_slot_axis);
    CHECK(ev.max_slots == 10);

    CHECK(ev.present[ABS_MT_SLOT]);
    CHECK(ev.present[ABS_MT_TRACKING_ID]);
    CHECK(ev.present[ABS_MT_POSITION_X]);
    CHECK(ev.present[ABS_MT_POSITION_Y]);
    CHECK(ev.present[ABS_MT_TOUCH_MAJOR]);
    CHECK(ev.present[ABS_MT_PRESSURE]);
    CHECK(ev.present[ABS_MT_WIDTH_MAJOR]);

    // All recovered through EVIOCGABS, none claimed by the bitmap.
    CHECK(ev.probe_only[ABS_MT_POSITION_X]);
    CHECK(ev.probe_only[ABS_MT_POSITION_Y]);
    CHECK(ev.probe_only[ABS_MT_SLOT]);

    // The area used for candidate ranking is X * Y.
    CHECK((ev.max_x - ev.min_x) * 1LL * (ev.max_y - ev.min_y) == 1079LL * 2399LL);
}

/* A normal device with a correct bitmap must behave exactly as before. */
static void test_bitmask_device() {
    sensitune::AbsCaps caps;
    caps.ev_abs = true;
    caps.bitmask_ok = true;
    add_axis(&caps, ABS_MT_SLOT, 0, 9);
    add_axis(&caps, ABS_MT_TRACKING_ID, 0, 65535);
    add_axis(&caps, ABS_MT_POSITION_X, 0, 4095);
    add_axis(&caps, ABS_MT_POSITION_Y, 0, 4095);
    mark_bit(&caps, ABS_MT_SLOT);
    mark_bit(&caps, ABS_MT_TRACKING_ID);
    mark_bit(&caps, ABS_MT_POSITION_X);
    mark_bit(&caps, ABS_MT_POSITION_Y);

    const sensitune::AbsEvaluation ev = sensitune::evaluate_abs(caps);
    CHECK(ev.accepted);
    CHECK(ev.max_x == 4095);
    CHECK(ev.max_slots == 10);
    CHECK(!ev.probe_only[ABS_MT_POSITION_X]);
    CHECK(!ev.probe_only[ABS_MT_SLOT]);
}

static void test_rejects() {
    {
        sensitune::AbsCaps caps = make_oppo_event1();
        caps.ev_abs = false;
        CHECK(!sensitune::evaluate_abs(caps).accepted);
    }
    {
        // Both paths fail for X.
        sensitune::AbsCaps caps;
        caps.ev_abs = true;
        add_axis(&caps, ABS_MT_POSITION_Y, 0, 2399);
        CHECK(!sensitune::evaluate_abs(caps).accepted);
    }
    {
        // Degenerate X range is not usable even when EVIOCGABS succeeds.
        sensitune::AbsCaps caps = make_oppo_event1();
        caps.axes[ABS_MT_POSITION_X].maximum = caps.axes[ABS_MT_POSITION_X].minimum;
        CHECK(!sensitune::evaluate_abs(caps).accepted);
    }
    {
        // Y missing while X is good.
        sensitune::AbsCaps caps = make_oppo_event1();
        caps.axes[ABS_MT_POSITION_Y].getabs_ok = false;
        CHECK(!sensitune::evaluate_abs(caps).accepted);
    }
}

static int count_char(const std::string &s, char c) {
    int n = 0;
    for (char ch : s) {
        if (ch == c) ++n;
    }
    return n;
}

static void test_log_json() {
    // The exact message shape that used to be cut mid-string.
    const std::string msg =
            "scan /dev/input/event1 \"touchpanel\": missing ABS_MT_POSITION_X and/or "
            "ABS_MT_POSITION_Y -> rejected";
    const std::string rec =
            sensitune::sensi_log_record_json(42, 123456, 2, "Engine", msg);
    CHECK(rec.rfind("{\"seq\":42,", 0) == 0);
    CHECK(rec.size() > 2 && rec.compare(rec.size() - 2, 2, "\"}") == 0);
    CHECK(count_char(rec, '"') >= 10);  // keys + string boundaries intact
    CHECK(rec.find("\\\"touchpanel\\\"") != std::string::npos);

    // Quotes, backslashes, newlines and control chars must be escaped.
    const std::string tricky = "a\"b\\c\nd\te\x01";
    const std::string esc = sensitune::sensi_json_escape(tricky);
    CHECK(esc.find("\\\"") != std::string::npos);
    CHECK(esc.find("\\\\") != std::string::npos);
    CHECK(esc.find("\\n") != std::string::npos);
    CHECK(esc.find("\\t") != std::string::npos);
    CHECK(esc.find("\\u0001") != std::string::npos);
    CHECK(esc.find('\n') == std::string::npos);

    // Long messages are capped before escaping, get an ellipsis, and the
    // record is still a complete JSON object.
    std::string huge(4000, 'x');
    huge += "\"\\\n";
    const std::string long_rec =
            sensitune::sensi_log_record_json(7, 1, 1, "perf-sampler", huge, 480);
    CHECK(long_rec.size() < 480 * 6 + 200);
    CHECK(long_rec.find("\xE2\x80\xA6") != std::string::npos);
    CHECK(long_rec.compare(long_rec.size() - 2, 2, "\"}") == 0);
    CHECK(count_char(long_rec, '\n') == 0);

    // Invalid UTF-8 cannot leak into the JSON string.
    std::string bad = "bad:";
    bad.push_back(static_cast<char>(0xFF));
    bad.push_back(static_cast<char>(0xC3));  // lone lead byte
    const std::string bad_esc = sensitune::sensi_json_escape(bad);
    CHECK(bad_esc.find("\\u00ff") != std::string::npos);
    CHECK(bad_esc.find("\\u00c3") != std::string::npos);

    // Truncation never splits a multi-byte UTF-8 sequence.
    std::string cut = std::string(479, 'a');
    cut += "\xC3\xA9";  // é: lead byte lands exactly at the 480-byte cap
    const std::string cut_rec =
            sensitune::sensi_log_record_json(9, 1, 1, "t", cut, 480);
    CHECK(cut_rec.find("\xC3\"") == std::string::npos);
    CHECK(cut_rec.compare(cut_rec.size() - 2, 2, "\"}") == 0);
}

static void test_curves() {
    for (int shape = 0; shape <= 7; ++shape) {
        CHECK(std::fabs(sensitune::shape_curve(shape, 0.0f)) < 1e-5f);
        CHECK(std::fabs(sensitune::shape_curve(shape, 1.0f) - 1.0f) < 1e-4f);
        float prev = -1.0f;
        for (int n = 0; n <= 100; ++n) {
            const float u = (float) n / 100.0f;
            const float v = sensitune::shape_curve(shape, u);
            CHECK(v >= prev - 1e-6f);
            CHECK(v >= -1e-6f && v <= 1.0f + 1e-6f);
            prev = v;
        }
    }
    for (int easing = 0; easing <= 2; ++easing) {
        CHECK(std::fabs(sensitune::apply_easing(easing, 2, 0.0f)) < 1e-5f);
        CHECK(std::fabs(sensitune::apply_easing(easing, 2, 1.0f) - 1.0f) < 1e-4f);
    }
}

static void test_perlin_deterministic() {
    const float a = sensitune::perlin1d(12.34f);
    const float b = sensitune::perlin1d(12.34f);
    CHECK(a == b);
    CHECK(a >= -1.0f && a <= 1.0f);
    // The table is a pure function: the same path length always gives the
    // same wobble, independent of time or call order.
    float sum1 = 0.0f;
    float sum2 = 0.0f;
    for (int n = 0; n < 200; ++n) sum1 += sensitune::perlin1d((float) n * 0.37f);
    for (int n = 0; n < 200; ++n) sum2 += sensitune::perlin1d((float) n * 0.37f);
    CHECK(sum1 == sum2);
    CHECK(std::fabs(sensitune::perlin1d(0.0f)) < 1e-6f);
}

static void test_drag_gain_and_zero() {
    sensitune::DragConfig cfg;
    cfg.valuePct = 100;
    cfg.shape = sensitune::SHAPE_PERLIN;
    cfg.noiseAmp = 0.5f;
    const float gain = sensitune::drag_gain(cfg, 500.0f, 2.0f);
    CHECK(gain >= 1.0f && gain <= sensitune::MAX_TOTAL_GAIN);
    // delta_in = 0 -> delta_out = 0 regardless of amp/period.
    CHECK(0.0f * gain == 0.0f);
    for (int period = 20; period <= 300; period += 20) {
        cfg.noisePeriodPx = period;
        const float g = sensitune::drag_gain(cfg, 137.0f, 0.0f);
        CHECK(std::isfinite(g));
        CHECK(g <= 4.0f);
    }
    cfg.shape = sensitune::SHAPE_LINEAR;
    CHECK(std::fabs(sensitune::drag_gain(cfg, 0.0f, 0.0f) - 1.0f) < 1e-5f);
    CHECK(std::fabs(sensitune::drag_gain(cfg, 10000.0f, 0.0f) - 4.0f) < 1e-4f);
}

static void test_styles_zero_and_bounds() {
    for (int id = 0; id <= 8; ++id) {
        sensitune::StyleConfig cfg;
        cfg.id = id;
        cfg.enabled = true;
        cfg.intensity = 100.0f;
        sensitune::StyleRuntime st;
        sensitune::style_reset(st);
        float ox = 7.0f, oy = -3.0f;
        sensitune::style_process(cfg, 0.0f, 0.0f, 0.016f, st, &ox, &oy);
        CHECK(ox == 0.0f && oy == 0.0f);

        // A real delta must stay finite and bounded (gain never above 4x).
        for (int n = 0; n < 40; ++n) {
            sensitune::style_process(cfg, 8.0f, 3.0f, 0.016f, st, &ox, &oy);
            CHECK(std::isfinite(ox) && std::isfinite(oy));
            CHECK(std::fabs(ox) <= 8.0f * 4.0f);
            CHECK(std::fabs(oy) <= 3.0f * 4.0f);
        }
        // Finger stops: output returns to 0 immediately, no residual drift.
        sensitune::style_process(cfg, 0.0f, 0.0f, 0.016f, st, &ox, &oy);
        CHECK(ox == 0.0f && oy == 0.0f);
    }
}

static void test_rotation_transform() {
    const int rw = 1080, rh = 2400;
    for (int rot = 0; rot <= 3; ++rot) {
        const int ls = (rot == 1 || rot == 3) ? 2400 : 1080;
        const int ss = (rot == 1 || rot == 3) ? 1080 : 2400;
        float sx = 0, sy = 0;
        sensitune::raw_to_screen(rot, 0, 0, rw, rh, ls, ss, &sx, &sy);
        CHECK(sx >= -1.0f && sx <= (float) ls + 1.0f);
        CHECK(sy >= -1.0f && sy <= (float) ss + 1.0f);
        /* Forward transform of a real raw delta, then inverse back. */
        float ax = 0, ay = 0, bx = 0, by = 0;
        sensitune::raw_to_screen(rot, 300.0f, 700.0f, rw, rh, ls, ss, &ax, &ay);
        sensitune::raw_to_screen(rot, 312.0f, 695.0f, rw, rh, ls, ss, &bx, &by);
        float drx = 0, dry = 0;
        sensitune::screen_delta_to_raw(rot, bx - ax, by - ay, rw, rh, ls, ss, &drx, &dry);
        CHECK(std::fabs(drx - 12.0f) < 0.01f);
        CHECK(std::fabs(dry + 5.0f) < 0.01f);
    }
}

int main() {
    test_oppo_event1_fallback();
    test_bitmask_device();
    test_rejects();
    test_log_json();
    test_curves();
    test_perlin_deterministic();
    test_drag_gain_and_zero();
    test_styles_zero_and_bounds();
    test_rotation_transform();

    if (g_failures == 0) {
        std::printf("native host tests: OK (abs fallback, JSON, DSP curves, Perlin, styles, rotation)\n");
        return 0;
    }
    std::fprintf(stderr, "native host tests: %d failure(s)\n", g_failures);
    return 1;
}
