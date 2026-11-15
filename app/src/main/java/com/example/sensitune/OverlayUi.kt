package com.example.sensitune

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout

/** Small shared helpers for both overlays (no Compose, no Fragments). */
object OverlayUi {

    data class Metric(val key: String, val label: String, val temperature: Boolean)

    val METRICS = listOf(
        Metric("fps", "FPS", false),
        Metric("cpu", "CPU°C", true),
        Metric("gpu", "GPU°C", true),
        Metric("battery", "Pin°C", true),
        Metric("ram", "RAM°C", true),
        Metric("skin", "Thân°C", true),
        Metric("shell", "Lưng°C", true),
        Metric("cpuPct", "CPU%", false),
        Metric("cpuFreq", "Xung CPU", false)
    )

    fun metric(key: String): Metric? = METRICS.firstOrNull { it.key == key }

    fun canDraw(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    fun overlayIntent(ctx: Context): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${ctx.packageName}")
    )

    fun dp(ctx: Context, value: Float): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    fun screenSize(ctx: Context): Point {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val p = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(p)
        return p
    }

    fun landscape(ctx: Context): Boolean =
        ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    fun rounded(color: Int, radiusDp: Float, ctx: Context, strokeColor: Int? = null): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = dp(ctx, radiusDp).toFloat()
        d.setColor(color)
        if (strokeColor != null) d.setStroke(dp(ctx, 1f), strokeColor)
        return d
    }

    fun tempColor(ctx: Context, prefs: Prefs, value: Double?): Int {
        if (value == null) return Color.parseColor("#B0BEC5")
        return when {
            value > prefs.tempCritC -> Color.parseColor("#FF5252")
            value > prefs.tempWarnC -> Color.parseColor("#FFD54F")
            else -> Color.WHITE
        }
    }
}

/** FrameLayout that reports every touch so panels can reset their auto-close timer. */
class TouchFrameLayout(context: Context) : FrameLayout(context) {

    var onInteract: (() -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        onInteract?.invoke()
        return super.dispatchTouchEvent(ev)
    }
}
