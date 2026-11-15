package com.example.sensitune

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Separate date/time HUD: "Fri Jun 7 12:59:59" (English weekday/month, 24h
 * clock). It is its own overlay window so it can stay visible when the
 * performance HUD is disabled, and it never mixes with temperature/CPU metrics.
 *
 * Like the perf HUD it is locked (touch-through) by default. Unlocking the HUD
 * in the floating menu makes both windows draggable; both also auto-lock after
 * 15 seconds. Font size follows the shared Prefs.hudTextScale so one setting
 * scales both HUDs.
 */
class ClockHud(private val ctx: Context, private val prefs: Prefs) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val format = SimpleDateFormat("EEE MMM d HH:mm:ss", Locale.ENGLISH)

    private var root: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false
    private var locked = true

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false

    private val autoLock = Runnable { if (attached && !locked) setLocked(true) }
    private val longPressLock = Runnable {
        if (attached && !locked && !moved) setLocked(true)
    }
    private val tick = object : Runnable {
        override fun run() {
            updateText()
            if (!attached) return
            val now = System.currentTimeMillis()
            handler.postDelayed(this, 1000L - (now % 1000L) + 20L)
        }
    }

    fun isShowing(): Boolean = attached

    fun show() {
        if (attached) {
            applyPrefs()
            return
        }
        if (!OverlayUi.canDraw(ctx)) {
            LogBuffer.warn(TAG, "clock HUD not shown: overlay permission missing")
            return
        }
        locked = prefs.hudLocked
        buildView()
        attach()
    }

    fun hide() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(autoLock)
        handler.removeCallbacks(longPressLock)
        detach()
        root = null
        params = null
    }

    /** Re-reads scale, alpha, lock and position after a preference change. */
    fun applyPrefs() {
        val v = root ?: return
        val p = params ?: return
        v.textSize = BASE_TEXT_SP * prefs.hudTextScale
        p.alpha = prefs.hudAlpha
        if (locked != prefs.hudLocked) {
            locked = prefs.hudLocked
            p.flags = flags()
        }
        v.setBackgroundColor(Color.TRANSPARENT)
        val drawable = OverlayUi.rounded(
            Color.parseColor("#CC000000"), 10f, ctx,
            if (!locked) Color.parseColor("#FFC107") else null
        )
        v.background = drawable
        if (attached) {
            try {
                wm.updateViewLayout(v, p)
            } catch (t: Throwable) {
                LogBuffer.error(TAG, "updateViewLayout(clock) failed", t)
            }
        }
    }

    fun setLocked(value: Boolean) {
        if (locked == value) {
            applyWindowFlags()
            return
        }
        locked = value
        prefs.hudLocked = value
        handler.removeCallbacks(autoLock)
        handler.removeCallbacks(longPressLock)
        applyWindowFlags()
        if (value) {
            savePosition()
        } else {
            handler.postDelayed(autoLock, AUTO_LOCK_MS)
        }
        LogBuffer.info(TAG, "clock HUD ${if (value) "locked" else "unlocked"}")
    }

    fun moveBy(dx: Int, dy: Int) {
        val p = params ?: return
        val size = OverlayUi.screenSize(ctx)
        p.x = clampX(p.x + dx, root?.width ?: 0, size.x)
        p.y = clampY(p.y + dy, root?.height ?: 0, size.y)
        updateLayout()
        savePosition()
    }

    fun onConfigurationChanged() {
        val p = params ?: return
        val landscape = OverlayUi.landscape(ctx)
        val size = OverlayUi.screenSize(ctx)
        val sx = prefs.clockX(landscape)
        val sy = prefs.clockY(landscape)
        p.x = if (sx >= 0) sx else p.x
        p.y = if (sy >= 0) sy else p.y
        p.x = clampX(p.x, root?.width ?: 0, size.x)
        p.y = clampY(p.y, root?.height ?: 0, size.y)
        updateLayout()
        savePosition()
    }

    // ------------------------------------------------------------------

    private fun buildView() {
        val tv = TextView(ctx).apply {
            text = format.format(Date())
            textSize = BASE_TEXT_SP * prefs.hudTextScale
            setTextColor(Color.WHITE)
            isSingleLine = true
            setPadding(dp(8f), dp(5f), dp(8f), dp(5f))
            background = OverlayUi.rounded(
                Color.parseColor("#CC000000"), 10f, ctx,
                if (!locked) Color.parseColor("#FFC107") else null
            )
            setOnTouchListener { _, ev -> handleTouch(ev) }
        }
        val landscape = OverlayUi.landscape(ctx)
        val size = OverlayUi.screenSize(ctx)
        val savedX = prefs.clockX(landscape)
        val savedY = prefs.clockY(landscape)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags(),
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = if (savedX >= 0) savedX else dp(12f)
        p.y = if (savedY >= 0) savedY else clampY(dp(48f), 0, size.y)
        p.alpha = prefs.hudAlpha
        root = tv
        params = p
    }

    private fun flags(): Int {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (locked) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return f
    }

    private fun applyWindowFlags() {
        val v = root ?: return
        val p = params ?: return
        p.flags = flags()
        v.background = OverlayUi.rounded(
            Color.parseColor("#CC000000"), 10f, ctx,
            if (!locked) Color.parseColor("#FFC107") else null
        )
        if (attached) updateLayout()
    }

    private fun attach() {
        val v = root ?: return
        val p = params ?: return
        if (attached) return
        try {
            wm.addView(v, p)
            attached = true
            updateText()
            handler.removeCallbacks(tick)
            handler.post(tick)
            if (!locked) handler.postDelayed(autoLock, AUTO_LOCK_MS)
            LogBuffer.info(TAG, "clock HUD attached at (${p.x}, ${p.y}) locked=$locked")
            v.post {
                if (attached) {
                    clampCurrent()
                    savePosition()
                }
            }
        } catch (t: Throwable) {
            attached = false
            LogBuffer.error(TAG, "addView(clock) failed", t)
        }
    }

    private fun detach() {
        val v = root ?: return
        if (!attached) return
        try {
            wm.removeViewImmediate(v)
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "removeView(clock) failed", t)
        } finally {
            attached = false
        }
    }

    private fun updateText() {
        try {
            root?.text = format.format(Date())
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "clock format failed", t)
        }
    }

    private fun updateLayout() {
        val v = root ?: return
        val p = params ?: return
        if (!attached) return
        try {
            wm.updateViewLayout(v, p)
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "updateViewLayout(clock) failed", t)
        }
    }

    private fun clampCurrent() {
        val p = params ?: return
        val size = OverlayUi.screenSize(ctx)
        p.x = clampX(p.x, root?.width ?: 0, size.x)
        p.y = clampY(p.y, root?.height ?: 0, size.y)
        updateLayout()
    }

    private fun savePosition() {
        val p = params ?: return
        prefs.setClockPos(p.x, p.y, OverlayUi.landscape(ctx))
    }

    private fun handleTouch(ev: MotionEvent): Boolean {
        val p = params ?: return false
        if (locked) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                startX = p.x
                startY = p.y
                moved = false
                handler.removeCallbacks(autoLock)
                handler.postDelayed(longPressLock, 1000)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (ev.rawX - downRawX).toInt()
                val dy = (ev.rawY - downRawY).toInt()
                if (abs(dx) > dp(4f) || abs(dy) > dp(4f)) {
                    moved = true
                    handler.removeCallbacks(longPressLock)
                }
                if (moved) {
                    val size = OverlayUi.screenSize(ctx)
                    p.x = clampX(startX + dx, root?.width ?: 0, size.x)
                    p.y = clampY(startY + dy, root?.height ?: 0, size.y)
                    updateLayout()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressLock)
                if (moved) {
                    savePosition()
                    LogBuffer.info(TAG, "clock HUD moved to (${p.x}, ${p.y})")
                }
                if (!locked) handler.postDelayed(autoLock, AUTO_LOCK_MS)
            }
        }
        return true
    }

    private fun clampX(x: Int, width: Int, screenW: Int): Int {
        val margin = dp(4f)
        return max(0, min(x, max(0, screenW - max(width, dp(48f)) - margin)))
    }

    private fun clampY(y: Int, height: Int, screenH: Int): Int {
        val margin = dp(4f)
        return max(0, min(y, max(0, screenH - max(height, dp(32f)) - margin)))
    }

    private fun dp(v: Float): Int = OverlayUi.dp(ctx, v)

    companion object {
        private const val TAG = "ClockHud"
        private const val BASE_TEXT_SP = 14f
        private const val AUTO_LOCK_MS = 15_000L
    }
}
