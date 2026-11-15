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

    var factorX: Float
        get() = sp.getFloat(KEY_X, 1.0f)
        set(value) = sp.edit().putFloat(KEY_X, value).apply()

    var factorY: Float
        get() = sp.getFloat(KEY_Y, 1.0f)
        set(value) = sp.edit().putFloat(KEY_Y, value).apply()

    var lockXY: Boolean
        get() = sp.getBoolean(KEY_LOCK_XY, true)
        set(value) = sp.edit().putBoolean(KEY_LOCK_XY, value).apply()

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
        const val KEY_LOCK_XY = "lock_xy"
        const val KEY_ENABLED = "enabled"
        const val KEY_RUN_BACKGROUND = "run_background"
        const val KEY_ONLY_IN_APPS = "only_in_apps"
        const val KEY_TEST_DONE = "test_done"
        const val KEY_TARGETS = "target_packages"
        const val KEY_OVERRIDES = "per_app_overrides"
    }
}
