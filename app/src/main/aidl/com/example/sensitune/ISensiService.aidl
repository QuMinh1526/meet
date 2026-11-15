// ISensiService.aidl
package com.example.sensitune;

interface ISensiService {
    /**
     * Absolute path of the app's native library directory. Sent by the app so the
     * shell process can System.load() libsensi_engine.so explicitly.
     */
    void setNativeLibraryPath(String path);

    /** true = grab touchscreen and apply factors, false = release completely to IDLE. */
    void setEnabled(boolean enabled);

    /** Realtime factor update; no lock, safe while the engine is running. */
    void setFactor(float x, float y);

    /** Must be sent at least every 3 seconds while the engine is active. */
    void heartbeat();

    /** JSON status: state, factor, device, counters, latency, last error... */
    String getStatus();

    /** JSON array of log records with seq > sinceSeq. */
    String getLogs(int sinceSeq);

    /** Ungrab + destroy the virtual device immediately. Never throws. */
    void emergencyRelease();

    /** Release everything and stop this service (called before unbind). */
    void destroy();

    /** Non-grabbing capability report (uinput, devices, SELinux...). */
    String diagnose();
}
