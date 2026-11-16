#ifndef SENSI_ENGINE_H
#define SENSI_ENGINE_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Error codes returned by every JNI entry point (never throw across JNI).
 * Kotlin maps these to Vietnamese user-facing messages.
 */
enum SensiErrorCode {
    SENSI_OK = 0,
    SENSI_ERR_NO_TOUCH_DEVICE = 1,
    SENSI_ERR_UINPUT_OPEN = 2,
    SENSI_ERR_UINPUT_SETUP = 3,
    SENSI_ERR_UINPUT_CREATE = 4,
    SENSI_ERR_UINPUT_NODE_MISSING = 5,
    SENSI_ERR_GRAB = 6,
    SENSI_ERR_BAD_ARG = 7,
    SENSI_ERR_NOT_READY = 8,
    SENSI_ERR_INTERNAL = 9,
    SENSI_ERR_NOT_SUPPORTED = 10
};

const char *sensi_strerror(int code);

int sensi_start(void);
int sensi_stop(const char *reason);
int sensi_set_enabled(int enabled);
int sensi_set_factor(float x, float y);
/* Display rotation as Display.ROTATION_0..3 (portrait 0/2, landscape 1/3). */
int sensi_set_rotation(int rotation);
/* true = forward events unchanged (k=1.0, no soft recenter) for overlay gestures. */
int sensi_set_scaling_suspended(int suspended);
int sensi_set_edge_margin(int percent);
int sensi_set_soft_recenter(int enabled);
int sensi_set_reanchor(int enabled);
int sensi_heartbeat(void);
int sensi_emergency_release(const char *reason);
char *sensi_get_status_json(void);
char *sensi_get_logs_json(int since_seq);
char *sensi_diagnose_json(void);

#ifdef __cplusplus
}
#endif

#endif // SENSI_ENGINE_H
