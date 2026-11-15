package com.example.sensitune

/**
 * JNI declarations for libsensi_engine.so. Loaded explicitly from the UserService
 * process with System.load(<nativeLibraryDir>/libsensi_engine.so).
 *
 * Every native function returns an error code (never throws through JNI).
 */
object NativeBridge {

    @JvmStatic external fun start(): Int
    @JvmStatic external fun stop(reason: String): Int
    @JvmStatic external fun setEnabled(enabled: Boolean): Int
    @JvmStatic external fun setFactor(x: Float, y: Float): Int
    @JvmStatic external fun heartbeat(): Int
    @JvmStatic external fun emergencyRelease(reason: String): Int
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
                "Tham số không hợp lệ (hệ số phải trong khoảng 0.5 - 2.5)."
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
