package com.example.sensitune

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * All persisted settings. Defaults are deliberately safe:
 * enabled = false (never auto-start), factors = 1.0, background = false.
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("sensi_prefs", Context.MODE_PRIVATE)

    /** Total gain is capped at 4.0x by the engine, so the UI never stores more. */
    var factorX: Float
        get() = sp.getFloat(KEY_X, 1.0f).coerceIn(0.5f, 4.0f)
        set(value) = sp.edit().putFloat(KEY_X, value.coerceIn(0.5f, 4.0f)).apply()

    var factorY: Float
        get() = sp.getFloat(KEY_Y, 1.0f).coerceIn(0.5f, 4.0f)
        set(value) = sp.edit().putFloat(KEY_Y, value.coerceIn(0.5f, 4.0f)).apply()

    // ------------------------------------------------------------------
    // Mode + shared STYLE + DRAG settings (engine reads them through AIDL)
    // ------------------------------------------------------------------

    /** 0 = Sensitivity, 1 = Drag. */
    var mode: Int
        get() = sp.getInt(KEY_MODE, 0).coerceIn(0, 1)
        set(value) = sp.edit().putInt(KEY_MODE, value.coerceIn(0, 1)).apply()

    /** Per-app mode used when "chỉ trong app đã chọn" is on. */
    fun modeFor(pkg: String?): Int {
        if (pkg == null) return mode
        val raw = sp.getString(KEY_PER_APP_MODE, "{}") ?: "{}"
        return try {
            JSONObject(raw).optInt(pkg, mode)
        } catch (t: Throwable) {
            mode
        }
    }

    fun setModeFor(pkg: String?, value: Int) {
        if (pkg == null) {
            mode = value
            return
        }
        val raw = sp.getString(KEY_PER_APP_MODE, "{}") ?: "{}"
        val obj = try {
            JSONObject(raw)
        } catch (t: Throwable) {
            JSONObject()
        }
        obj.put(pkg, value.coerceIn(0, 1))
        sp.edit().putString(KEY_PER_APP_MODE, obj.toString()).apply()
    }

    fun clearModeFor(pkg: String) {
        val raw = sp.getString(KEY_PER_APP_MODE, "{}") ?: "{}"
        val obj = try {
            JSONObject(raw)
        } catch (t: Throwable) {
            return
        }
        obj.remove(pkg)
        sp.edit().putString(KEY_PER_APP_MODE, obj.toString()).apply()
    }

    var styleEnabled: Boolean
        get() = sp.getBoolean(KEY_STYLE_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_STYLE_ENABLED, value).apply()

    /** 0 Raw, 1 Smooth, 2 Adaptive, 3 Snappy, 4 Elastic, 5 Precision, 6 S-curve, 7 Power, 8 Stepped. */
    var styleId: Int
        get() = sp.getInt(KEY_STYLE_ID, 0).coerceIn(0, 8)
        set(value) = sp.edit().putInt(KEY_STYLE_ID, value.coerceIn(0, 8)).apply()

    var styleIntensity: Int
        get() = sp.getInt(KEY_STYLE_INTENSITY, 50).coerceIn(0, 100)
        set(value) = sp.edit().putInt(KEY_STYLE_INTENSITY, value.coerceIn(0, 100)).apply()

    var styleStiffness: Float
        get() = sp.getFloat(KEY_STYLE_STIFFNESS, 320f).coerceIn(40f, 900f)
        set(value) = sp.edit().putFloat(KEY_STYLE_STIFFNESS, value.coerceIn(40f, 900f)).apply()

    var styleDamping: Float
        get() = sp.getFloat(KEY_STYLE_DAMPING, 28f).coerceIn(2f, 120f)
        set(value) = sp.edit().putFloat(KEY_STYLE_DAMPING, value.coerceIn(2f, 120f)).apply()

    /** 0..100, mapped to 1.0x + 3.0x * extra. */
    var dragValue: Int
        get() = sp.getInt(KEY_DRAG_VALUE, 50).coerceIn(0, 100)
        set(value) = sp.edit().putInt(KEY_DRAG_VALUE, value.coerceIn(0, 100)).apply()

    /** 0..7 base curves, 8 Perlin, 9 Velocity. */
    var dragShape: Int
        get() = sp.getInt(KEY_DRAG_SHAPE, 0).coerceIn(0, 9)
        set(value) = sp.edit().putInt(KEY_DRAG_SHAPE, value.coerceIn(0, 9)).apply()

    /** 0 In, 1 Out, 2 InOut. */
    var dragEasing: Int
        get() = sp.getInt(KEY_DRAG_EASING, 0).coerceIn(0, 2)
        set(value) = sp.edit().putInt(KEY_DRAG_EASING, value.coerceIn(0, 2)).apply()

    /** 0 Any, 1 Horizontal, 2 Vertical, 3 Up, 4 Down. */
    var dragDirection: Int
        get() = sp.getInt(KEY_DRAG_DIRECTION, 0).coerceIn(0, 4)
        set(value) = sp.edit().putInt(KEY_DRAG_DIRECTION, value.coerceIn(0, 4)).apply()

    var dragLengthRef: Int
        get() = sp.getInt(KEY_DRAG_LREF, 220).coerceIn(20, 2000)
        set(value) = sp.edit().putInt(KEY_DRAG_LREF, value.coerceIn(20, 2000)).apply()

    var dragStopOutside: Boolean
        get() = sp.getBoolean(KEY_DRAG_STOP, false)
        set(value) = sp.edit().putBoolean(KEY_DRAG_STOP, value).apply()

    var dragNoiseAmp: Int
        get() = sp.getInt(KEY_DRAG_AMP, 15).coerceIn(0, 50)
        set(value) = sp.edit().putInt(KEY_DRAG_AMP, value.coerceIn(0, 50)).apply()

    var dragNoisePeriod: Int
        get() = sp.getInt(KEY_DRAG_PERIOD, 90).coerceIn(20, 300)
        set(value) = sp.edit().putInt(KEY_DRAG_PERIOD, value.coerceIn(20, 300)).apply()

    var dragVelocityRef: Float
        get() = sp.getFloat(KEY_DRAG_VREF, 2.0f).coerceIn(0.1f, 20f)
        set(value) = sp.edit().putFloat(KEY_DRAG_VREF, value.coerceIn(0.1f, 20f)).apply()

    var dragSizeDp: Int
        get() = sp.getInt(KEY_DRAG_SIZE_DP, 120).coerceIn(48, 240)
        set(value) = sp.edit().putInt(KEY_DRAG_SIZE_DP, value.coerceIn(48, 240)).apply()

    var dragAlpha: Float
        get() = sp.getFloat(KEY_DRAG_ALPHA, 0.35f).coerceIn(0.05f, 0.8f)
        set(value) = sp.edit().putFloat(KEY_DRAG_ALPHA, value.coerceIn(0.05f, 0.8f)).apply()

    var dragLocked: Boolean
        get() = sp.getBoolean(KEY_DRAG_LOCKED, true)
        set(value) = sp.edit().putBoolean(KEY_DRAG_LOCKED, value).apply()

    fun dragX(landscape: Boolean): Int =
        sp.getInt(if (landscape) KEY_DRAG_X_LAND else KEY_DRAG_X_PORT, -1)

    fun dragY(landscape: Boolean): Int =
        sp.getInt(if (landscape) KEY_DRAG_Y_LAND else KEY_DRAG_Y_PORT, -1)

    fun setDragPos(x: Int, y: Int, landscape: Boolean) {
        sp.edit()
            .putInt(if (landscape) KEY_DRAG_X_LAND else KEY_DRAG_X_PORT, x)
            .putInt(if (landscape) KEY_DRAG_Y_LAND else KEY_DRAG_Y_PORT, y)
            .apply()
    }

    fun resetDragDefaults() {
        sp.edit()
            .putInt(KEY_DRAG_VALUE, 50)
            .putInt(KEY_DRAG_SHAPE, 0)
            .putInt(KEY_DRAG_EASING, 0)
            .putInt(KEY_DRAG_DIRECTION, 0)
            .putInt(KEY_DRAG_LREF, 220)
            .putBoolean(KEY_DRAG_STOP, false)
            .putInt(KEY_DRAG_AMP, 15)
            .putInt(KEY_DRAG_PERIOD, 90)
            .putFloat(KEY_DRAG_VREF, 2.0f)
            .putInt(KEY_DRAG_SIZE_DP, 120)
            .putFloat(KEY_DRAG_ALPHA, 0.35f)
            .putBoolean(KEY_STYLE_ENABLED, false)
            .putInt(KEY_STYLE_ID, 0)
            .putInt(KEY_STYLE_INTENSITY, 50)
            .putFloat(KEY_STYLE_STIFFNESS, 320f)
            .putFloat(KEY_STYLE_DAMPING, 28f)
            .apply()
    }

    var lockXY: Boolean
        get() = sp.getBoolean(KEY_LOCK_XY, true)
        set(value) = sp.edit().putBoolean(KEY_LOCK_XY, value).apply()

    /** Edge buffer in percent of each raw axis length (0..25). */
    var edgeMarginPct: Int
        get() = sp.getInt(KEY_EDGE_MARGIN, 12).coerceIn(0, 25)
        set(value) = sp.edit().putInt(KEY_EDGE_MARGIN, value.coerceIn(0, 25)).apply()

    /** Fade the virtual offset to 0 near an edge so a swipe can turn around. */
    var softRecenter: Boolean
        get() = sp.getBoolean(KEY_SOFT_RECENTER, true)
        set(value) = sp.edit().putBoolean(KEY_SOFT_RECENTER, value).apply()

    /** Optional lift + re-touch at the centre when the virtual point hits an edge. */
    var reanchor: Boolean
        get() = sp.getBoolean(KEY_REANCHOR, false)
        set(value) = sp.edit().putBoolean(KEY_REANCHOR, value).apply()

    /** Desired state. Persisted only to show the correct UI; engine never auto-starts. */
    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    var runBackground: Boolean
        get() = sp.getBoolean(KEY_RUN_BACKGROUND, false)
        set(value) = sp.edit().putBoolean(KEY_RUN_BACKGROUND, value).apply()

    var onlyInSelectedApps: Boolean
        get() = sp.getBoolean(KEY_ONLY_IN_APPS, false)
        set(value) = sp.edit().putBoolean(KEY_ONLY_IN_APPS, value).apply()

    /** True after "Thử 10 giây" ran once in this app process session. */
    var testDoneThisSession: Boolean
        get() = sp.getBoolean(KEY_TEST_DONE, false)
        set(value) = sp.edit().putBoolean(KEY_TEST_DONE, value).apply()

    // ------------------------------------------------------------------
    // Overlay: floating menu + performance HUD
    // ------------------------------------------------------------------

    var floatMenuEnabled: Boolean
        get() = sp.getBoolean(KEY_FLOAT_MENU, false)
        set(value) = sp.edit().putBoolean(KEY_FLOAT_MENU, value).apply()

    var hudEnabled: Boolean
        get() = sp.getBoolean(KEY_HUD_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_HUD_ENABLED, value).apply()

    /** Separate date/time HUD ("Fri Jun 7 12:59:59"), independent of the perf HUD. */
    var clockHudEnabled: Boolean
        get() = sp.getBoolean(KEY_CLOCK_HUD_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_CLOCK_HUD_ENABLED, value).apply()

    fun clockX(landscape: Boolean): Int =
        sp.getInt(if (landscape) KEY_CLOCK_X_LAND else KEY_CLOCK_X_PORT, -1)

    fun clockY(landscape: Boolean): Int =
        sp.getInt(if (landscape) KEY_CLOCK_Y_LAND else KEY_CLOCK_Y_PORT, -1)

    fun setClockPos(x: Int, y: Int, landscape: Boolean) {
        sp.edit()
            .putInt(if (landscape) KEY_CLOCK_X_LAND else KEY_CLOCK_X_PORT, x)
            .putInt(if (landscape) KEY_CLOCK_Y_LAND else KEY_CLOCK_Y_PORT, y)
            .apply()
    }

    /** Locked by default: the HUD is not touchable and cannot be dragged by accident. */
    var hudLocked: Boolean
        get() = sp.getBoolean(KEY_HUD_LOCKED, true)
        set(value) = sp.edit().putBoolean(KEY_HUD_LOCKED, value).apply()

    var hudTextScale: Float
        get() = sp.getFloat(KEY_HUD_TEXT_SCALE, 1.0f).coerceIn(0.7f, 1.8f)
        set(value) = sp.edit().putFloat(KEY_HUD_TEXT_SCALE, value.coerceIn(0.7f, 1.8f)).apply()

    /** Untrusted-touch rule on Android 12: overlay opacity must stay <= 0.8. */
    var hudAlpha: Float
        get() = sp.getFloat(KEY_HUD_ALPHA, 0.8f).coerceIn(0.2f, 0.8f)
        set(value) = sp.edit().putFloat(KEY_HUD_ALPHA, value.coerceIn(0.2f, 0.8f)).apply()

    /** false = one row, true = vertical column. */
    var hudVertical: Boolean
        get() = sp.getBoolean(KEY_HUD_VERTICAL, true)
        set(value) = sp.edit().putBoolean(KEY_HUD_VERTICAL, value).apply()

    var tempWarnC: Float
        get() = sp.getFloat(KEY_TEMP_WARN, 45f)
        set(value) = sp.edit().putFloat(KEY_TEMP_WARN, value.coerceIn(30f, 90f)).apply()

    var tempCritC: Float
        get() = sp.getFloat(KEY_TEMP_CRIT, 50f)
        set(value) = sp.edit().putFloat(KEY_TEMP_CRIT, value.coerceIn(35f, 100f)).apply()

    /** Returns -1 when no position was saved for that orientation yet. */
    fun hudX(landscape: Boolean): Int =
        sp.getInt(if (landscape) KEY_HUD_X_LAND else KEY_HUD_X_PORT, -1)

    fun hudY(landscape: Boolean): Int =
        sp.getInt(if (landscape) KEY_HUD_Y_LAND else KEY_HUD_Y_PORT, -1)

    fun setHudPos(x: Int, y: Int, landscape: Boolean) {
        sp.edit()
            .putInt(if (landscape) KEY_HUD_X_LAND else KEY_HUD_X_PORT, x)
            .putInt(if (landscape) KEY_HUD_Y_LAND else KEY_HUD_Y_PORT, y)
            .apply()
    }

    fun menuX(): Int = sp.getInt(KEY_MENU_X, -1)
    fun menuY(): Int = sp.getInt(KEY_MENU_Y, -1)
    fun setMenuPos(x: Int, y: Int) {
        sp.edit().putInt(KEY_MENU_X, x).putInt(KEY_MENU_Y, y).apply()
    }

    /** Ordered list of every HUD metric and whether it is enabled. */
    fun hudMetrics(): MutableList<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        val raw = sp.getString(KEY_HUD_METRICS, null)
        if (raw != null) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val name = o.optString("n", "")
                    if (name.isNotEmpty()) out.add(Pair(name, o.optBoolean("on", false)))
                }
            } catch (t: Throwable) {
                LogBuffer.error("Prefs", "hudMetrics parse failed", t)
            }
        }
        if (out.isEmpty()) {
            for (name in DEFAULT_HUD_ORDER) {
                out.add(Pair(name, name in DEFAULT_HUD_ON))
            }
        }
        return out
    }

    fun setHudMetrics(list: List<Pair<String, Boolean>>) {
        val arr = JSONArray()
        for ((name, on) in list) {
            arr.put(JSONObject().put("n", name).put("on", on))
        }
        sp.edit().putString(KEY_HUD_METRICS, arr.toString()).apply()
    }

    /** CSV of enabled metrics, in display order; empty when nothing is on. */
    fun hudEnabledCsv(): String = hudMetrics()
        .filter { it.second }
        .joinToString(",") { it.first }

    fun selectedPackages(): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        val raw = sp.getString(KEY_TARGETS, "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) out.add(arr.getString(i))
        } catch (t: Throwable) {
            LogBuffer.error("Prefs", "selectedPackages parse failed", t)
        }
        return out
    }

    fun setSelectedPackages(packages: Collection<String>) {
        val arr = JSONArray()
        packages.forEach { arr.put(it) }
        sp.edit().putString(KEY_TARGETS, arr.toString()).apply()
    }

    fun perAppFactor(pkg: String): Pair<Float, Float>? {
        val raw = sp.getString(KEY_OVERRIDES, "{}") ?: "{}"
        return try {
            val obj = JSONObject(raw)
            if (!obj.has(pkg)) return null
            val arr = obj.getJSONArray(pkg)
            Pair(arr.getDouble(0).toFloat(), arr.getDouble(1).toFloat())
        } catch (t: Throwable) {
            null
        }
    }

    fun setPerAppFactor(pkg: String, factor: Pair<Float, Float>?) {
        val raw = sp.getString(KEY_OVERRIDES, "{}") ?: "{}"
        val obj = try {
            JSONObject(raw)
        } catch (t: Throwable) {
            JSONObject()
        }
        if (factor == null) {
            obj.remove(pkg)
        } else {
            obj.put(pkg, JSONArray().put(factor.first.toDouble()).put(factor.second.toDouble()))
        }
        sp.edit().putString(KEY_OVERRIDES, obj.toString()).apply()
    }

    fun allOverrides(): Map<String, Pair<Float, Float>> {
        val out = HashMap<String, Pair<Float, Float>>()
        val raw = sp.getString(KEY_OVERRIDES, "{}") ?: "{}"
        try {
            val obj = JSONObject(raw)
            for (key in obj.keys()) {
                val arr = obj.getJSONArray(key)
                out[key] = Pair(arr.getDouble(0).toFloat(), arr.getDouble(1).toFloat())
            }
        } catch (t: Throwable) {
            LogBuffer.error("Prefs", "allOverrides parse failed", t)
        }
        return out
    }

    private companion object {
        const val KEY_X = "factor_x"
        const val KEY_Y = "factor_y"
        const val KEY_MODE = "mode"
        const val KEY_PER_APP_MODE = "per_app_mode"
        const val KEY_STYLE_ENABLED = "style_enabled"
        const val KEY_STYLE_ID = "style_id"
        const val KEY_STYLE_INTENSITY = "style_intensity"
        const val KEY_STYLE_STIFFNESS = "style_stiffness"
        const val KEY_STYLE_DAMPING = "style_damping"
        const val KEY_DRAG_VALUE = "drag_value"
        const val KEY_DRAG_SHAPE = "drag_shape"
        const val KEY_DRAG_EASING = "drag_easing"
        const val KEY_DRAG_DIRECTION = "drag_direction"
        const val KEY_DRAG_LREF = "drag_lref"
        const val KEY_DRAG_STOP = "drag_stop_outside"
        const val KEY_DRAG_AMP = "drag_noise_amp"
        const val KEY_DRAG_PERIOD = "drag_noise_period"
        const val KEY_DRAG_VREF = "drag_velocity_ref"
        const val KEY_DRAG_SIZE_DP = "drag_size_dp"
        const val KEY_DRAG_ALPHA = "drag_alpha"
        const val KEY_DRAG_LOCKED = "drag_locked"
        const val KEY_DRAG_X_PORT = "drag_x_portrait"
        const val KEY_DRAG_Y_PORT = "drag_y_portrait"
        const val KEY_DRAG_X_LAND = "drag_x_landscape"
        const val KEY_DRAG_Y_LAND = "drag_y_landscape"
        const val KEY_LOCK_XY = "lock_xy"
        const val KEY_EDGE_MARGIN = "edge_margin_pct"
        const val KEY_SOFT_RECENTER = "soft_recenter"
        const val KEY_REANCHOR = "reanchor"
        const val KEY_ENABLED = "enabled"
        const val KEY_RUN_BACKGROUND = "run_background"
        const val KEY_ONLY_IN_APPS = "only_in_apps"
        const val KEY_TEST_DONE = "test_done"
        const val KEY_TARGETS = "target_packages"
        const val KEY_OVERRIDES = "per_app_overrides"
        const val KEY_FLOAT_MENU = "float_menu_enabled"
        const val KEY_HUD_ENABLED = "hud_enabled"
        const val KEY_CLOCK_HUD_ENABLED = "clock_hud_enabled"
        const val KEY_CLOCK_X_PORT = "clock_x_portrait"
        const val KEY_CLOCK_Y_PORT = "clock_y_portrait"
        const val KEY_CLOCK_X_LAND = "clock_x_landscape"
        const val KEY_CLOCK_Y_LAND = "clock_y_landscape"
        const val KEY_HUD_LOCKED = "hud_locked"
        const val KEY_HUD_TEXT_SCALE = "hud_text_scale"
        const val KEY_HUD_ALPHA = "hud_alpha"
        const val KEY_HUD_VERTICAL = "hud_vertical"
        const val KEY_HUD_METRICS = "hud_metrics"
        const val KEY_HUD_X_PORT = "hud_x_portrait"
        const val KEY_HUD_Y_PORT = "hud_y_portrait"
        const val KEY_HUD_X_LAND = "hud_x_landscape"
        const val KEY_HUD_Y_LAND = "hud_y_landscape"
        const val KEY_MENU_X = "menu_x"
        const val KEY_MENU_Y = "menu_y"
        const val KEY_TEMP_WARN = "temp_warn_c"
        const val KEY_TEMP_CRIT = "temp_crit_c"

        val DEFAULT_HUD_ORDER = listOf(
            "fps", "cpu", "gpu", "battery", "ram", "skin", "shell", "cpuPct", "cpuFreq"
        )
        val DEFAULT_HUD_ON = setOf("fps", "cpu", "gpu", "battery")
    }
}
