package com.example.sensitune.ui.kit

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.TypedValue
import android.view.ContextThemeWrapper
import com.example.sensitune.R

/**
 * Shared styling helpers for the ui.kit widgets.
 *
 * Widgets resolve their colors from the current theme, so the same code renders
 * correctly in the light Activity theme and in the dark overlay theme. Nothing
 * here depends on AppCompat, Compose, Fragment or an Activity.
 */
/** Every kit widget implements this so detached overlays release their callbacks. */
interface KitReleasable {
    fun release()
}

object Kit {

    const val MIN_TOUCH_DP = 40f

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun sp(context: Context, value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            value,
            context.resources.displayMetrics
        )

    fun themeColor(context: Context, attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        if (!context.theme.resolveAttribute(attr, tv, true)) return fallback
        return try {
            if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data
        } catch (t: Throwable) {
            tv.data
        }
    }

    fun isDark(context: Context): Boolean {
        val night = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (night) return true
        val bg = themeColor(context, android.R.attr.windowBackground, Color.WHITE)
        return luminance(bg) < 0.5
    }

    fun textPrimary(context: Context): Int =
        themeColor(context, android.R.attr.textColorPrimary, if (isDark(context)) Color.WHITE else Color.BLACK)

    fun textSecondary(context: Context): Int =
        themeColor(
            context,
            android.R.attr.textColorSecondary,
            if (isDark(context)) 0xFFB0BEC5.toInt() else 0xFF607D8B.toInt()
        )

    fun accent(context: Context): Int =
        themeColor(context, android.R.attr.colorAccent, 0xFF1565C0.toInt())

    fun cardBackground(context: Context): Int =
        themeColor(
            context,
            android.R.attr.windowBackground,
            if (isDark(context)) 0xFF263238.toInt() else Color.WHITE
        )

    fun cardStroke(context: Context): Int =
        if (isDark(context)) 0x33FFFFFF else 0x22000000

    fun danger(): Int = 0xFFD32F2F.toInt()
    fun ok(): Int = 0xFF2E7D32.toInt()
    fun neutral(): Int = 0xFF90A4AE.toInt()

    /** Overlay windows must use the dark theme so Switch/SeekBar draw correctly. */
    fun overlayTheme(context: Context): Context =
        ContextThemeWrapper(context, R.style.Theme_SensiTune_Overlay)

    private fun luminance(color: Int): Double {
        val r = Color.red(color) / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}
