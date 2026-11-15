/*
 * JSON helpers for the native log ring, shared with host unit tests.
 *
 * The old code formatted each log record into a fixed 160-byte buffer while an
 * escaped message could be several KB, so records were cut mid-string and
 * getLogs() produced invalid JSON. Records are now assembled as full strings:
 * the message is truncated (UTF-8 safe) BEFORE escaping and the closing quote
 * and brace are always appended.
 */

#ifndef SENSITUNE_SENSI_JSON_H
#define SENSITUNE_SENSI_JSON_H

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <string>

namespace sensitune {

/* Length of a valid UTF-8 sequence at p, or 0 when the bytes are invalid. */
inline std::size_t utf8_seq_len(const unsigned char *p, std::size_t avail) {
    if (!p || avail == 0) return 0;
    const unsigned char c = p[0];
    auto in = [&](std::size_t i, unsigned char lo, unsigned char hi) {
        return i < avail && p[i] >= lo && p[i] <= hi;
    };
    if (c < 0x80) return 1;
    if (c >= 0xC2 && c <= 0xDF) return in(1, 0x80, 0xBF) ? 2 : 0;
    if (c >= 0xE0 && c <= 0xEF) {
        unsigned char lo = 0x80, hi = 0xBF;
        if (c == 0xE0) lo = 0xA0;
        if (c == 0xED) hi = 0x9F;
        return (in(1, lo, hi) && in(2, 0x80, 0xBF)) ? 3 : 0;
    }
    if (c >= 0xF0 && c <= 0xF4) {
        unsigned char lo = 0x80, hi = 0xBF;
        if (c == 0xF0) lo = 0x90;
        if (c == 0xF4) hi = 0x8F;
        return (in(1, lo, hi) && in(2, 0x80, 0xBF) && in(3, 0x80, 0xBF)) ? 4 : 0;
    }
    return 0;
}

/* Standard JSON string escaping; invalid UTF-8 bytes become \u00XX. */
inline std::string sensi_json_escape(const std::string &s) {
    std::string out;
    out.reserve(s.size() + 16);
    const unsigned char *p = reinterpret_cast<const unsigned char *>(s.data());
    const std::size_t n = s.size();
    for (std::size_t i = 0; i < n;) {
        const unsigned char c = p[i];
        switch (c) {
            case '"': out += "\\\""; ++i; continue;
            case '\\': out += "\\\\"; ++i; continue;
            case '\n': out += "\\n"; ++i; continue;
            case '\r': out += "\\r"; ++i; continue;
            case '\t': out += "\\t"; ++i; continue;
            default: break;
        }
        if (c < 0x20) {
            char buf[8];
            std::snprintf(buf, sizeof(buf), "\\u%04x", c);
            out += buf;
            ++i;
            continue;
        }
        if (c < 0x80) {
            out += static_cast<char>(c);
            ++i;
            continue;
        }
        const std::size_t len = utf8_seq_len(p + i, n - i);
        if (len > 0) {
            out.append(reinterpret_cast<const char *>(p + i), len);
            i += len;
        } else {
            char buf[8];
            std::snprintf(buf, sizeof(buf), "\\u%04x", c);
            out += buf;
            ++i;
        }
    }
    return out;
}

/* Shrinks str to <= max_bytes without leaving a partial UTF-8 sequence. */
inline bool truncate_utf8(std::string *str, std::size_t max_bytes) {
    if (!str || str->size() <= max_bytes) return false;
    str->resize(max_bytes);
    while (!str->empty()) {
        const unsigned char c = static_cast<unsigned char>(str->back());
        if ((c & 0xC0) == 0x80 || c >= 0xC0) {
            str->pop_back();
        } else {
            break;
        }
    }
    return true;
}

/*
 * Builds one complete {"seq":..,"ts":..,"level":..,"thread":"..","msg":".."}
 * object. The message is capped at max_msg_bytes (before escaping) and gets an
 * ellipsis when it had to be cut.
 */
inline std::string sensi_log_record_json(long long seq, long long ts, int level,
                                         const std::string &thread,
                                         const std::string &msg,
                                         std::size_t max_msg_bytes = 480) {
    std::string body = msg;
    const bool cut = truncate_utf8(&body, max_msg_bytes);
    std::string escaped = sensi_json_escape(body);
    if (cut) escaped += "\xE2\x80\xA6";  // U+2026 HORIZONTAL ELLIPSIS

    std::string thread_short = thread;
    truncate_utf8(&thread_short, 64);

    std::string out;
    out.reserve(escaped.size() + thread_short.size() + 96);
    out += "{\"seq\":";
    out += std::to_string(seq);
    out += ",\"ts\":";
    out += std::to_string(ts);
    out += ",\"level\":";
    out += std::to_string(level);
    out += ",\"thread\":\"";
    out += sensi_json_escape(thread_short);
    out += "\",\"msg\":\"";
    out += escaped;
    out += "\"}";
    return out;
}

}  // namespace sensitune

#endif  // SENSITUNE_SENSI_JSON_H
