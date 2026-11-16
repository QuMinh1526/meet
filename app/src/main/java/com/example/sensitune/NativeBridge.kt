package com.example.sensitune

/**
 * JNI declarations for libsensi_engine.so. Loaded explicitly from the UserService
 * process with System.load(<nativeLibraryDir>/libsensi_engine.so).
 *
 * Every native function returns an error code (never throws through JNI).
 */
object NativeBridge {

    @Volatile
    private var previewLoaded = false

    /**
     * Loads the engine in the app process ONLY for the pure preview functions
     * (CurvePreview/TestPad). Never calls start(), so no grab can happen here.
     */
    @Synchronized
    fun ensurePreviewLoaded(): Boolean {
        if (previewLoaded) return true
        return try {
            System.loadLibrary("sensi_engine")
            previewLoaded = true
            true
        } catch (t: Throwable) {
            LogBuffer.warn("NativeBridge", "preview library unavailable: ${t.message}")
            false
        }
    }

    @JvmStatic external fun start(): Int
    @JvmStatic external fun stop(reason: String): Int
    @JvmStatic external fun setEnabled(enabled: Boolean): Int
    @JvmStatic external fun setFactor(x: Float, y: Float): Int
    @JvmStatic external fun setRotation(rotation: Int): Int
    @JvmStatic external fun setScalingSuspended(suspended: Boolean): Int
    @JvmStatic external fun setEdgeMargin(percent: Int): Int
    @JvmStatic external fun setSoftRecenter(enabled: Boolean): Int
    @JvmStatic external fun setReanchor(enabled: Boolean): Int
    @JvmStatic external fun setMode(mode: Int): Int
    @JvmStatic external fun setStyle(
        style: Int,
        enabled: Boolean,
        intensity: Int,
        stiffness: Float,
        damping: Float
    ): Int
    @JvmStatic external fun setDragConfig(
        valuePct: Int,
        shape: Int,
        easing: Int,
        direction: Int,
        lengthRefPx: Int,
        stopOutside: Boolean,
        noiseAmpPct: Int,
        noisePeriodPx: Int,
        velocityRefPxMs: Float
    ): Int
    @JvmStatic external fun setDragZone(
        cx: Int,
        cy: Int,
        radius: Int,
        screenW: Int,
        screenH: Int,
        rotation: Int
    ): Int
    @JvmStatic external fun heartbeat(): Int
    @JvmStatic external fun emergencyRelease(reason: String): Int
    /** TestPad only: one frame through the real STYLE code (thread-local state). */
    @JvmStatic external fun previewReset()
    @JvmStatic external fun previewStyle(
        style: Int,
        intensity: Int,
        stiffness: Float,
        damping: Float,
        dx: Float,
        dy: Float,
        dtMs: Float
    ): FloatArray?

    /** Pure preview of extra(u) for the drag curve; no engine state touched. */
    @JvmStatic external fun dragPreviewExtra(
        shape: Int,
        easing: Int,
        noiseAmp: Float,
        noisePeriod: Float,
        u: Float
    ): Float

    @JvmStatic external fun getStatus(): String
    @JvmStatic external fun getLogs(sinceSeq: Int): String
    @JvmStatic external fun diagnose(): String
    @JvmStatic external fun nativeVersion(): Int

    const val OK = 0
    const val ERR_NO_TOUCH_DEVICE = 1
    const val ERR_UINPUT_OPEN = 2
    const val ERR_UINPUT_SETUP = 3
    const val ERR_UINPUT_CREATE = 4
    const val ERR_UINPUT_NODE_MISSING = 5
    const val ERR_GRAB = 6
    const val ERR_BAD_ARG = 7
    const val ERR_NOT_READY = 8
    const val ERR_INTERNAL = 9
    const val ERR_NOT_SUPPORTED = 10

    fun errorMessage(code: Int, detail: String? = null): String {
        val base = when (code) {
            OK -> "OK"
            ERR_NO_TOUCH_DEVICE ->
                "Không tìm thấy màn hình cảm ứng phù hợp (EV_ABS + ABS_MT_POSITION_X/Y với max > min)."
            ERR_UINPUT_OPEN ->
                "Không mở được /dev/uinput: Permission denied (SELinux chặn?) - xem log."
            ERR_UINPUT_SETUP ->
                "Thiết lập thiết bị ảo thất bại (UI_DEV_SETUP/UI_ABS_SETUP)."
            ERR_UINPUT_CREATE ->
                "Không tạo được thiết bị ảo (UI_DEV_CREATE thất bại)."
            ERR_UINPUT_NODE_MISSING ->
                "Không xác minh được thiết bị ảo (sysfs/UI_GET_SYSNAME, /dev/input, EVIOCGNAME) - đã hủy, KHÔNG grab."
            ERR_GRAB ->
                "Không grab được cảm ứng (EVIOCGRAB thất bại) - cảm ứng vẫn bình thường."
            ERR_BAD_ARG ->
                "Tham số không hợp lệ (hệ số phải trong khoảng 0.5 - 10.0)."
            ERR_NOT_READY ->
                "Engine native chưa sẵn sàng (thư viện chưa load?)."
            ERR_INTERNAL ->
                "Lỗi nội bộ engine."
            ERR_NOT_SUPPORTED ->
                "Máy này không hỗ trợ (thiếu uinput hoặc cảm ứng không tương thích)."
            else -> "Lỗi không xác định ($code)."
        }
        return if (detail.isNullOrBlank()) base else "$base Chi tiết: $detail"
    }
}
