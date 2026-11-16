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

    /**
     * Display.ROTATION_0..3. X = horizontal movement of the current screen, so
     * portrait applies factorX to raw X and landscape applies it to raw Y.
     * Factors are pinned per finger when its TRACKING_ID arrives.
     */
    void setRotation(int rotation);

    /**
     * true = forward touches unchanged (k=1.0, no soft recenter) while KEEPING
     * the grab and the virtual device, so overlay sliders track the finger.
     * Auto-expires after 10 s unless renewed.
     */
    void setScalingSuspended(boolean suspended);

    /** Edge buffer as a percentage (0..25) of each raw axis length. */
    void setEdgeMargin(int percent);

    /** Soft recenter: fade the offset to 0 near a screen edge (default on). */
    void setSoftRecenter(boolean enabled);

    /** Optional lift + re-touch at the centre when the virtual point hits an edge. */
    void setReanchor(boolean enabled);

    /** 0 = Sensitivity, 1 = Drag. Pinned per finger at touch down. */
    void setMode(int mode);

    /** style 0..8, enabled, intensity 0..100, spring stiffness/damping. */
    void setStyle(int style, boolean enabled, int intensity, float stiffness, float damping);

    /** value 0..100, shape 0..9, easing 0..2, direction 0..4, Lref, stop, amp%, period, vRef. */
    void setDragConfig(int valuePct, int shape, int easing, int direction,
                       int lengthRefPx, boolean stopOutside, int noiseAmpPct,
                       int noisePeriodPx, float velocityRefPxMs);

    /** Circle in screen px; radius 0 = invalid = full pass-through. */
    void setDragZone(int cx, int cy, int radius, int screenW, int screenH, int rotation);

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

    /**
     * Tells the shell process which performance metrics the HUD currently needs
     * (CSV: fps,cpu,gpu,battery,ram,skin,shell,cpuPct,cpuFreq) and which package
     * is in the foreground. Empty package = let the shell detect it.
     */
    void configurePerf(String metricsCsv, String foregroundPackage);

    /** One JSON snapshot of the configured metrics; never throws. */
    String getPerfSnapshot();
}
