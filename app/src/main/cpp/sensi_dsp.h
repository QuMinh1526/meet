/*
 * Pure DSP helpers shared by the engine and the host unit tests.
 *
 * Everything in this header is a deterministic function of the finger delta:
 * no timers, no threads, no random numbers, no clock reads. A zero input delta
 * always produces a zero output delta.
 */
#ifndef SENSITUNE_SENSI_DSP_H
#define SENSITUNE_SENSI_DSP_H

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace sensitune {

enum Mode {
    MODE_SENSITIVITY = 0,
    MODE_DRAG = 1
};

enum StyleId {
    STYLE_RAW = 0,
    STYLE_SMOOTH = 1,
    STYLE_ADAPTIVE = 2,
    STYLE_SNAPPY = 3,
    STYLE_ELASTIC = 4,
    STYLE_PRECISION = 5,
    STYLE_SCURVE = 6,
    STYLE_POWER = 7,
    STYLE_STEPPED = 8
};

enum DragShape {
    SHAPE_LINEAR = 0,
    SHAPE_SINE = 1,
    SHAPE_QUAD = 2,
    SHAPE_CUBIC = 3,
    SHAPE_QUART = 4,
    SHAPE_QUINT = 5,
    SHAPE_EXPO = 6,
    SHAPE_CIRC = 7,
    SHAPE_PERLIN = 8,
    SHAPE_VELOCITY = 9
};

enum EasingForm {
    EASE_IN = 0,
    EASE_OUT = 1,
    EASE_IN_OUT = 2
};

enum DragDirection {
    DIR_ANY = 0,
    DIR_HORIZONTAL = 1,
    DIR_VERTICAL = 2,
    DIR_UP = 3,
    DIR_DOWN = 4
};

constexpr float MAX_TOTAL_GAIN = 4.0f;

struct StyleConfig {
    int id = STYLE_RAW;
    bool enabled = false;
    /* 0..100. */
    float intensity = 0.0f;
    float stiffness = 320.0f;
    float damping = 28.0f;
};

struct DragConfig {
    /* 0..100 -> up to 3.0x extra on top of 1.0x. */
    int valuePct = 50;
    int shape = SHAPE_LINEAR;
    int easing = EASE_IN;
    int direction = DIR_ANY;
    int lengthRefPx = 220;
    bool stopOutside = false;
    /* 0..0.5 -> how much noise is added on top of the base curve. */
    float noiseAmp = 0.15f;
    int noisePeriodPx = 90;
    float velocityRefPxMs = 2.0f;
};

struct ZoneConfig {
    bool valid = false;
    int cx = 0;
    int cy = 0;
    int radius = 0;
    int screenW = 0;
    int screenH = 0;
    int rotation = 0;
};

struct EngineConfig {
    int mode = MODE_SENSITIVITY;
    StyleConfig style;
    DragConfig drag;
    ZoneConfig zone;
};

struct StyleRuntime {
    float fx = 0.0f;
    float fy = 0.0f;   // smoothed / adaptive / elastic state
    float px = 0.0f;
    float py = 0.0f;   // spring position
    float vx = 0.0f;
    float vy = 0.0f;   // spring velocity
};

inline void style_reset(StyleRuntime &s) {
    s = StyleRuntime();
}

inline float clampf(float v, float lo, float hi) {
    if (!std::isfinite(v)) return lo;
    return v < lo ? lo : (v > hi ? hi : v);
}

/* --------------------------------------------------------------------- */
/* Base curves: f(0) = 0, f(1) = 1, monotonic on [0,1].                  */
/* --------------------------------------------------------------------- */

inline float shape_curve(int shape, float u) {
    u = clampf(u, 0.0f, 1.0f);
    switch (shape) {
        case SHAPE_SINE: return 1.0f - std::cos(3.14159265358979323846f * u * 0.5f);
        case SHAPE_QUAD: return u * u;
        case SHAPE_CUBIC: return u * u * u;
        case SHAPE_QUART: return u * u * u * u;
        case SHAPE_QUINT: return u * u * u * u * u;
        case SHAPE_EXPO:
            return (std::pow(2.0f, 10.0f * u) - 1.0f) / 1023.0f;
        case SHAPE_CIRC: {
            float d = 1.0f - u * u;
            if (d < 0.0f) d = 0.0f;
            return 1.0f - std::sqrt(d);
        }
        case SHAPE_LINEAR:
        default: return u;
    }
}

inline float apply_easing(int easing, int shape, float u) {
    u = clampf(u, 0.0f, 1.0f);
    switch (easing) {
        case EASE_OUT: return 1.0f - shape_curve(shape, 1.0f - u);
        case EASE_IN_OUT:
            return u < 0.5f
                   ? shape_curve(shape, 2.0f * u) * 0.5f
                   : 1.0f - shape_curve(shape, 2.0f * (1.0f - u)) * 0.5f;
        case EASE_IN:
        default: return shape_curve(shape, u);
    }
}

/* --------------------------------------------------------------------- */
/* 1D Perlin noise with a fixed permutation table (no rand, no seed).    */
/* Returns a deterministic value in [-1, 1].                             */
/* --------------------------------------------------------------------- */

inline const int *perlin_perm() {
    static const int PERM[512] = {
        151,160,137,91,90,15,131,13,201,95,96,53,194,233,7,225,
        140,36,103,30,69,142,8,99,37,240,21,10,23,190,6,148,
        247,120,234,75,0,26,197,62,94,252,219,203,117,35,11,32,
        57,177,33,88,237,149,56,87,174,20,125,136,171,168,68,175,
        74,165,71,134,139,48,27,166,77,146,158,231,83,111,229,122,
        60,211,133,230,220,105,92,41,55,46,245,40,244,102,143,54,
        65,25,63,161,1,216,80,73,209,76,132,187,208,89,18,169,
        200,196,135,130,116,188,159,86,164,100,109,198,173,186,3,64,
        52,217,226,250,124,123,5,202,38,147,118,126,255,82,85,212,
        207,206,59,227,47,16,58,17,182,189,28,42,223,183,170,213,
        119,248,152,2,44,154,163,70,221,153,101,155,167,43,172,9,
        129,22,39,253,19,98,108,110,79,113,224,232,178,185,112,104,
        218,246,97,228,251,34,242,193,238,210,144,12,191,179,162,241,
        81,51,145,235,249,14,239,107,49,192,214,31,181,199,106,157,
        184,84,204,176,115,121,50,45,127,4,150,254,138,236,205,93,
        222,114,67,29,24,72,243,141,128,195,78,66,215,61,156,180,
        151,160,137,91,90,15,131,13,201,95,96,53,194,233,7,225,
        140,36,103,30,69,142,8,99,37,240,21,10,23,190,6,148,
        247,120,234,75,0,26,197,62,94,252,219,203,117,35,11,32,
        57,177,33,88,237,149,56,87,174,20,125,136,171,168,68,175,
        74,165,71,134,139,48,27,166,77,146,158,231,83,111,229,122,
        60,211,133,230,220,105,92,41,55,46,245,40,244,102,143,54,
        65,25,63,161,1,216,80,73,209,76,132,187,208,89,18,169,
        200,196,135,130,116,188,159,86,164,100,109,198,173,186,3,64,
        52,217,226,250,124,123,5,202,38,147,118,126,255,82,85,212,
        207,206,59,227,47,16,58,17,182,189,28,42,223,183,170,213,
        119,248,152,2,44,154,163,70,221,153,101,155,167,43,172,9,
        129,22,39,253,19,98,108,110,79,113,224,232,178,185,112,104,
        218,246,97,228,251,34,242,193,238,210,144,12,191,179,162,241,
        81,51,145,235,249,14,239,107,49,192,214,31,181,199,106,157,
        184,84,204,176,115,121,50,45,127,4,150,254,138,236,205,93,
        222,114,67,29,24,72,243,141,128,195,78,66,215,61,156,180
    };
    return PERM;
}

inline float perlin1d(float x) {
    const int *perm = perlin_perm();
    const float fx = std::floor(x);
    const int xi = (int) fx;
    const float xf = x - fx;
    const int a = perm[xi & 255];
    const int b = perm[(xi + 1) & 255];
    const float ga = (a & 1) ? 1.0f : -1.0f;
    const float gb = (b & 1) ? 1.0f : -1.0f;
    const float n0 = ga * xf;
    const float n1 = gb * (xf - 1.0f);
    const float t = xf * xf * xf * (xf * (xf * 6.0f - 15.0f) + 10.0f);
    /* Gradient noise above peaks at +-0.5; scale so the range is +-1. */
    const float v = (n0 + t * (n1 - n0)) * 2.0f;
    return clampf(v, -1.0f, 1.0f);
}

inline bool direction_allows(int direction, float dx, float dy) {
    const float ax = std::fabs(dx);
    const float ay = std::fabs(dy);
    switch (direction) {
        case DIR_HORIZONTAL: return ax >= ay;
        case DIR_VERTICAL: return ay > ax;
        case DIR_UP: return dy < 0.0f && ay >= ax;
        case DIR_DOWN: return dy > 0.0f && ay >= ax;
        case DIR_ANY:
        default: return true;
    }
}

/*
 * extra in [0,1] for the drag mode.
 *   L       total finger path in screen px since touch down
 *   period  noise period in px (Perlin only)
 *   speed   smoothed finger speed in px/ms (velocity shape only)
 */
inline float drag_extra(const DragConfig &cfg, float L, float speed) {
    const float lref = std::max(1.0f, (float) cfg.lengthRefPx);
    const float u = clampf(L / lref, 0.0f, 1.0f);
    if (cfg.shape == SHAPE_PERLIN) {
        const float period = std::max(1.0f, (float) cfg.noisePeriodPx);
        const float base = apply_easing(cfg.easing, SHAPE_LINEAR, u);
        const float noise = perlin1d(L / period);
        return clampf(base + clampf(cfg.noiseAmp, 0.0f, 0.5f) * noise, 0.0f, 1.0f);
    }
    if (cfg.shape == SHAPE_VELOCITY) {
        const float vref = std::max(0.05f, cfg.velocityRefPxMs);
        const float s = clampf(speed / vref, 0.0f, 1.0f);
        return apply_easing(cfg.easing, SHAPE_LINEAR, s);
    }
    return apply_easing(cfg.easing, cfg.shape, u);
}

inline float drag_gain(const DragConfig &cfg, float L, float speed) {
    const float v = clampf((float) cfg.valuePct / 100.0f, 0.0f, 1.0f);
    const float extra = drag_extra(cfg, L, speed);
    const float gain = 1.0f + v * 3.0f * extra;
    return clampf(gain, 1.0f, MAX_TOTAL_GAIN);
}

/* --------------------------------------------------------------------- */
/* STYLE: transforms one frame of finger delta before the mode gain.     */
/* --------------------------------------------------------------------- */

inline float style_est_latency_us(const StyleConfig &cfg) {
    if (!cfg.enabled) return 0.0f;
    const float i = clampf(cfg.intensity / 100.0f, 0.0f, 1.0f);
    switch (cfg.id) {
        case STYLE_SMOOTH:
        case STYLE_ADAPTIVE:
            return i * 8000.0f;
        case STYLE_ELASTIC:
            return i * 6000.0f;
        case STYLE_PRECISION:
        case STYLE_SCURVE:
        case STYLE_POWER:
        case STYLE_STEPPED:
            return i * 3000.0f;
        case STYLE_SNAPPY:
        case STYLE_RAW:
        default:
            return 0.0f;
    }
}

inline void style_process(const StyleConfig &cfg, float dx, float dy, float dt_s,
                          StyleRuntime &st, float *ox, float *oy) {
    *ox = 0.0f;
    *oy = 0.0f;
    /* Hard rule: no finger delta -> no output, no residual spring motion. */
    if (dx == 0.0f && dy == 0.0f) {
        st.px = 0.0f;
        st.py = 0.0f;
        st.vx = 0.0f;
        st.vy = 0.0f;
        return;
    }
    if (!cfg.enabled || cfg.id == STYLE_RAW || cfg.id == STYLE_SNAPPY) {
        *ox = dx;
        *oy = dy;
        return;
    }

    const float i = clampf(cfg.intensity / 100.0f, 0.0f, 1.0f);
    if (dt_s <= 0.0f || dt_s > 0.25f) dt_s = 0.016f;
    const float mag = std::sqrt(dx * dx + dy * dy);
    const float speed = mag / dt_s;

    switch (cfg.id) {
        case STYLE_SMOOTH: {
            /* Added latency is capped at 8 ms even at intensity 100. */
            const float tau = 0.001f + 0.007f * i;
            const float a = 1.0f - std::exp(-dt_s / tau);
            st.fx += a * (dx - st.fx);
            st.fy += a * (dy - st.fy);
            *ox = st.fx;
            *oy = st.fy;
            return;
        }
        case STYLE_ADAPTIVE: {
            /* 1 euro filter on the delta: slow -> strong, fast -> almost none. */
            const float mincut = 20.0f + 100.0f * (1.0f - i);
            const float beta = 0.004f + 0.040f * i;
            const float cutoff = mincut + beta * speed;
            const float tau = 1.0f / (2.0f * 3.14159265358979323846f * cutoff);
            const float a = 1.0f / (1.0f + tau / dt_s);
            st.fx += a * (dx - st.fx);
            st.fy += a * (dy - st.fy);
            *ox = st.fx;
            *oy = st.fy;
            return;
        }
        case STYLE_ELASTIC: {
            const float k = clampf(cfg.stiffness, 40.0f, 900.0f);
            const float c = clampf(cfg.damping, 2.0f, 120.0f);
            const int steps = std::max(1, std::min(8, (int) std::ceil(dt_s / 0.004f)));
            const float h = dt_s / (float) steps;
            for (int n = 0; n < steps; ++n) {
                const float ax = k * (dx - st.px) - c * st.vx;
                const float ay = k * (dy - st.py) - c * st.vy;
                st.vx += ax * h;
                st.vy += ay * h;
                st.px += st.vx * h;
                st.py += st.vy * h;
            }
            /* Overshoot budget: at most 15% of this frame and at most 12 px. */
            const float budget = mag + std::min(mag * 0.15f, 12.0f);
            const float omag = std::sqrt(st.px * st.px + st.py * st.py);
            if (omag > budget && omag > 0.0001f) {
                const float s = budget / omag;
                st.px *= s;
                st.py *= s;
            }
            *ox = st.px;
            *oy = st.py;
            return;
        }
        case STYLE_PRECISION: {
            const float s = clampf(speed / 1.2f, 0.0f, 1.0f);
            const float g = 1.0f - i * 0.65f * (1.0f - s);
            *ox = dx * g;
            *oy = dy * g;
            return;
        }
        case STYLE_SCURVE: {
            const float s = clampf(speed / 1.2f, 0.0f, 1.0f);
            const float sig = 1.0f / (1.0f + std::exp(-6.0f * (s - 0.45f)));
            const float g = 1.0f - i * 0.6f * (1.0f - sig);
            *ox = dx * g;
            *oy = dy * g;
            return;
        }
        case STYLE_POWER: {
            const float s = clampf(speed / 1.2f, 0.0f, 1.0f);
            const float p = 0.5f + 1.5f * i;
            const float g = clampf(std::pow(std::max(s, 0.0001f), p), 0.35f, 1.0f);
            *ox = dx * g;
            *oy = dy * g;
            return;
        }
        case STYLE_STEPPED: {
            const int steps = std::max(1, (int) std::lround(1.0f + 5.0f * i));
            const float s = clampf(speed / 1.2f, 0.0f, 1.0f);
            const float q = std::round(s * (float) steps) / (float) steps;
            const float g = 0.4f + 0.6f * q;
            *ox = dx * g;
            *oy = dy * g;
            return;
        }
        default:
            *ox = dx;
            *oy = dy;
            return;
    }
}

/* --------------------------------------------------------------------- */
/* Raw <-> screen transform for all four display rotations.              */
/* rw = maxX + 1, rh = maxY + 1 (raw axis lengths).                      */
/* --------------------------------------------------------------------- */

inline void raw_to_screen(int rotation, float rx, float ry, int rw, int rh,
                          int sw, int sh, float *sx, float *sy) {
    const float RW = (float) std::max(1, rw);
    const float RH = (float) std::max(1, rh);
    switch (rotation) {
        case 1:
            *sx = ry * (float) sw / RH;
            *sy = ((float) (rw - 1) - rx) * (float) sh / RW;
            break;
        case 2:
            *sx = ((float) (rw - 1) - rx) * (float) sw / RW;
            *sy = ((float) (rh - 1) - ry) * (float) sh / RH;
            break;
        case 3:
            *sx = ((float) (rh - 1) - ry) * (float) sw / RH;
            *sy = rx * (float) sh / RW;
            break;
        case 0:
        default:
            *sx = rx * (float) sw / RW;
            *sy = ry * (float) sh / RH;
            break;
    }
}

inline void screen_delta_to_raw(int rotation, float dx, float dy, int rw, int rh,
                                int sw, int sh, float *drx, float *dry) {
    const float RW = (float) std::max(1, rw);
    const float RH = (float) std::max(1, rh);
    switch (rotation) {
        case 1:
            *dry = dx * RH / (float) sw;
            *drx = -dy * RW / (float) sh;
            break;
        case 2:
            *drx = -dx * RW / (float) sw;
            *dry = -dy * RH / (float) sh;
            break;
        case 3:
            *dry = -dx * RH / (float) sw;
            *drx = dy * RW / (float) sh;
            break;
        case 0:
        default:
            *drx = dx * RW / (float) sw;
            *dry = dy * RH / (float) sh;
            break;
    }
}

} // namespace sensitune

#endif // SENSITUNE_SENSI_DSP_H
