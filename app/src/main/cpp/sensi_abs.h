/*
 * Pure ABS capability evaluation, shared by the engine and host unit tests.
 *
 * Android kernels are not consistent about EVIOCGBIT(EV_ABS): some devices
 * (e.g. OPPO CPH2113 / touchpanel) report EV_ABS but an all-zero ABS bitmap
 * while EVIOCGABS still returns the real axis ranges. The engine therefore
 * probes every interesting axis directly and this header decides whether the
 * device is usable without trusting the bitmap alone.
 *
 * No Android headers are used here so the file can be compiled on the host.
 */

#ifndef SENSITUNE_SENSI_ABS_H
#define SENSITUNE_SENSI_ABS_H

#include <linux/input.h>

#include <cstddef>
#include <cstdint>

namespace sensitune {

/* (ABS_MAX / 8) + 1 bytes: the buffer handed to EVIOCGBIT(EV_ABS, len). */
constexpr int kAbsBitmapBytes = (ABS_MAX / 8) + 1;

/* Must stay in sync with MAX_SLOTS in sensi_engine.cpp. */
constexpr int kAbsMaxSlots = 32;

inline bool abs_bit_test(const uint8_t *bits, std::size_t bytes, int code) {
    if (!bits || code < 0) return false;
    const std::size_t byte = static_cast<std::size_t>(code) / 8u;
    if (byte >= bytes) return false;
    return ((bits[byte] >> (code % 8)) & 1u) != 0;
}

inline void abs_bit_set(uint8_t *bits, std::size_t bytes, int code) {
    if (!bits || code < 0) return;
    const std::size_t byte = static_cast<std::size_t>(code) / 8u;
    if (byte >= bytes) return;
    bits[byte] = static_cast<uint8_t>(bits[byte] | (1u << (code % 8)));
}

/* One ABS code: what the bitmap claims and what EVIOCGABS actually returned. */
struct AxisProbe {
    bool bit_set = false;
    bool getabs_ok = false;
    int minimum = 0;
    int maximum = 0;
    int resolution = 0;
    int fuzz = 0;
    int flat = 0;
};

struct AbsCaps {
    bool ev_abs = false;
    bool bitmask_ok = false;
    AxisProbe axes[ABS_CNT];
};

struct AbsEvaluation {
    bool accepted = false;
    const char *reason = "";
    int min_x = 0;
    int max_x = 0;
    int min_y = 0;
    int max_y = 0;
    int res_x = 0;
    int res_y = 0;
    bool has_slot_axis = false;
    int max_slots = 1;
    /* Axes to advertise on the virtual device (only ones with real ranges). */
    bool present[ABS_CNT] = {};
    /* present[] came from EVIOCGABS even though the bitmap did not claim it. */
    bool probe_only[ABS_CNT] = {};
};

/* Axes probed even when the bitmap says nothing (the OPPO fallback). */
inline bool abs_probe_axis(int code) {
    switch (code) {
        case ABS_MT_SLOT:
        case ABS_MT_TRACKING_ID:
        case ABS_MT_POSITION_X:
        case ABS_MT_POSITION_Y:
        case ABS_MT_PRESSURE:
        case ABS_PRESSURE:
        case ABS_MT_TOUCH_MAJOR:
        case ABS_TOOL_WIDTH:
        case ABS_MT_WIDTH_MAJOR:
            return true;
        default:
            return false;
    }
}

inline AbsEvaluation evaluate_abs(const AbsCaps &caps) {
    AbsEvaluation out;
    if (!caps.ev_abs) {
        out.reason = "device has no EV_ABS";
        return out;
    }

    for (int code = 0; code < ABS_CNT; ++code) {
        const AxisProbe &a = caps.axes[code];
        const bool wanted = a.bit_set || abs_probe_axis(code);
        if (a.getabs_ok && wanted) {
            out.present[code] = true;
            out.probe_only[code] = !a.bit_set;
        }
    }

    const AxisProbe &px = caps.axes[ABS_MT_POSITION_X];
    const AxisProbe &py = caps.axes[ABS_MT_POSITION_Y];

    if (!out.present[ABS_MT_POSITION_X]) {
        out.reason = "ABS_MT_POSITION_X missing (bitmap empty and EVIOCGABS failed)";
        return out;
    }
    if (px.maximum <= px.minimum) {
        out.reason = "ABS_MT_POSITION_X has a degenerate range";
        return out;
    }
    if (!out.present[ABS_MT_POSITION_Y]) {
        out.reason = "ABS_MT_POSITION_Y missing (bitmap empty and EVIOCGABS failed)";
        return out;
    }
    if (py.maximum <= py.minimum) {
        out.reason = "ABS_MT_POSITION_Y has a degenerate range";
        return out;
    }

    out.min_x = px.minimum;
    out.max_x = px.maximum;
    out.min_y = py.minimum;
    out.max_y = py.maximum;
    out.res_x = px.resolution;
    out.res_y = py.resolution;

    const AxisProbe &slot = caps.axes[ABS_MT_SLOT];
    out.has_slot_axis = out.present[ABS_MT_SLOT] || slot.bit_set;
    if (out.present[ABS_MT_SLOT]) {
        int slots = slot.maximum + 1;
        if (slots < 1) slots = 1;
        if (slots > kAbsMaxSlots) slots = kAbsMaxSlots;
        out.max_slots = slots;
    } else if (out.has_slot_axis) {
        /* Bitmap claims MT slots but EVIOCGABS failed: keep the old default. */
        out.max_slots = 10;
    } else {
        out.max_slots = 1;
    }

    out.accepted = true;
    out.reason = "accepted";
    return out;
}

}  // namespace sensitune

#endif  // SENSITUNE_SENSI_ABS_H
