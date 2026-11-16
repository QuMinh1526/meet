package com.example.sensitune

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Visual circle of the DRAG mode. The window itself never decides which finger is
 * scaled: the engine checks the raw touch coordinates against this circle.
 *
 * Locked (default) = FLAG_NOT_TOUCHABLE, so every touch goes straight to the app.
 * Unlocked = the circle can be dragged; scaling is suspended while unlocked so the
 * drag gesture is 1:1, and it locks itself again after 15 s.
 */
class DragZoneView(
    private val ctx: Context,
    private val prefs: Prefs,
    private val cb: Callbacks
) {

    interface Callbacks {
        fun onZoneChanged()
        fun onUnlocked()
        fun onLocked()
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var view: CircleView? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false
    private var locked = true

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false

    private val autoLock = Runnable {
        if (attached && !locked) setLocked(true)
    }

    private fun radiusPx(): Int = OverlayUi.dp(ctx, prefs.dragSizeDp.toFloat() / 2f)

    fun isShowing(): Boolean = attached

    fun show() {
        if (attached) {
            refresh()
            return
        }
        if (!OverlayUi.canDraw(ctx)) {
            LogBuffer.warn(TAG, "drag zone not shown: overlay permission missing")
            return
        }
        locked = prefs.dragLocked
        build()
        attach()
    }

    fun hide() {
        handler.removeCallbacks(autoLock)
        val v = view ?: return
        if (!attached) return
        try {
            wm.removeViewImmediate(v)
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "removeView(drag zone) failed", t)
        } finally {
            attached = false
            view = null
            params = null
        }
    }

    /** Re-applies size, opacity, lock state and position. */
    fun refresh() {
        val v = view ?: return
        val p = params ?: return
        if (locked != prefs.dragLocked) {
            locked = prefs.dragLocked
            handler.removeCallbacks(autoLock)
            if (locked) cb.onLocked() else {
                handler.postDelayed(autoLock, AUTO_LOCK_MS)
                cb.onUnlocked()
            }
        }
        val r = radiusPx()
        val size = r * 2 + OverlayUi.dp(ctx, 8f)
        p.width = size
        p.height = size
        p.alpha = prefs.dragAlpha
        p.flags = flags()
        val (cx, cy) = currentCenter()
        p.x = cx - size / 2
        p.y = cy - size / 2
        v.invalidate()
        if (attached) {
            try {
                wm.updateViewLayout(v, p)
            } catch (t: Throwable) {
                LogBuffer.error(TAG, "updateViewLayout(drag zone) failed", t)
            }
        }
        cb.onZoneChanged()
    }

    fun onConfigurationChanged() {
        if (!attached) return
        /* Rebuild from the position saved for the new orientation. */
        hide()
        show()
        cb.onZoneChanged()
    }

    fun setLocked(value: Boolean) {
        if (locked == value) {
            applyFlags()
            return
        }
        locked = value
        prefs.dragLocked = value
        handler.removeCallbacks(autoLock)
        applyFlags()
        if (value) {
            savePosition()
            cb.onLocked()
        } else {
            handler.postDelayed(autoLock, AUTO_LOCK_MS)
            cb.onUnlocked()
        }
        LogBuffer.info(TAG, "drag zone ${if (value) "locked" else "unlocked"}")
    }

    fun isLocked(): Boolean = locked

    /** Sends center + radius + screen size to the engine (screen px, not dp). */
    fun sendToEngine() {
        val (cx, cy) = currentCenter()
        val size = OverlayUi.screenSize(ctx)
        val rotation = currentRotation()
        ShizukuHelper.setDragZone(cx, cy, radiusPx(), size.x, size.y, rotation)
    }

    fun sendInvalidZone() {
        val size = OverlayUi.screenSize(ctx)
        ShizukuHelper.setDragZone(0, 0, 0, max(1, size.x), max(1, size.y), currentRotation())
    }

    private fun currentCenter(): Pair<Int, Int> {
        val size = OverlayUi.screenSize(ctx)
        if (params == null) {
            val landscape = OverlayUi.landscape(ctx)
            val sx = prefs.dragX(landscape)
            val sy = prefs.dragY(landscape)
            val cx = if (sx >= 0) sx else size.x / 2
            val cy = if (sy >= 0) sy else size.y / 2
            return Pair(cx, cy)
        }
        val p = params!!
        return Pair(p.x + p.width / 2, p.y + p.height / 2)
    }

    private fun currentRotation(): Int = try {
        val dm = ctx.getSystemService(android.hardware.display.DisplayManager::class.java)
        dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation
            ?: android.view.Surface.ROTATION_0
    } catch (t: Throwable) {
        android.view.Surface.ROTATION_0
    }

    private fun flags(): Int {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (locked) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return f
    }

    private fun applyFlags() {
        val v = view ?: return
        val p = params ?: return
        p.flags = flags()
        v.invalidate()
        if (attached) {
            try {
                wm.updateViewLayout(v, p)
            } catch (t: Throwable) {
                LogBuffer.error(TAG, "applyFlags(drag zone) failed", t)
            }
        }
    }

    private fun build() {
        val r = radiusPx()
        val size = r * 2 + OverlayUi.dp(ctx, 8f)
        val circle = CircleView(ctx, this)
        val landscape = OverlayUi.landscape(ctx)
        val screen = OverlayUi.screenSize(ctx)
        val sx = prefs.dragX(landscape)
        val sy = prefs.dragY(landscape)
        val cx = if (sx >= 0) sx else screen.x / 2
        val cy = if (sy >= 0) sy else screen.y / 2
        val p = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags(),
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = cx - size / 2
        p.y = cy - size / 2
        p.alpha = prefs.dragAlpha
        view = circle
        params = p
    }

    private fun attach() {
        val v = view ?: return
        val p = params ?: return
        try {
            wm.addView(v, p)
            attached = true
            if (!locked) {
                handler.postDelayed(autoLock, AUTO_LOCK_MS)
                cb.onUnlocked()
            }
            LogBuffer.info(TAG, "drag zone attached at (${p.x}, ${p.y}) locked=$locked")
        } catch (t: Throwable) {
            attached = false
            view = null
            params = null
            LogBuffer.error(TAG, "addView(drag zone) failed", t)
        }
    }

    private fun savePosition() {
        if (params == null) return
        val (cx, cy) = currentCenter()
        prefs.setDragPos(cx, cy, OverlayUi.landscape(ctx))
    }

    private fun handleTouch(ev: MotionEvent): Boolean {
        if (locked) return false
        val p = params ?: return false
        val size = OverlayUi.screenSize(ctx)
        val dragSize = p.width
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                startX = p.x
                startY = p.y
                moved = false
                handler.removeCallbacks(autoLock)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (ev.rawX - downRawX).toInt()
                val dy = (ev.rawY - downRawY).toInt()
                if (abs(dx) > OverlayUi.dp(ctx, 4f) || abs(dy) > OverlayUi.dp(ctx, 4f)) {
                    moved = true
                }
                if (moved) {
                    p.x = max(0, min(startX + dx, max(0, size.x - dragSize)))
                    p.y = max(0, min(startY + dy, max(0, size.y - dragSize)))
                    try {
                        wm.updateViewLayout(view, p)
                    } catch (t: Throwable) {
                        LogBuffer.error(TAG, "move drag zone failed", t)
                    }
                    cb.onZoneChanged()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (moved) {
                    savePosition()
                    cb.onZoneChanged()
                    LogBuffer.info(TAG, "drag zone moved")
                }
                if (!locked) handler.postDelayed(autoLock, AUTO_LOCK_MS)
            }
        }
        return true
    }

    private inner class CircleView(context: Context, private val host: DragZoneView) :
        View(context) {

        init {
            isClickable = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = min(width, height) / 2f - OverlayUi.dp(ctx, 4f)
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor(if (locked) "#221565C0" else "#441565C0")
            canvas.drawCircle(cx, cy, r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = OverlayUi.dp(ctx, 2f).toFloat()
            paint.color = if (locked) Color.parseColor("#881565C0")
            else Color.parseColor("#FFFFC107")
            canvas.drawCircle(cx, cy, r, paint)
            paint.strokeWidth = OverlayUi.dp(ctx, 1f).toFloat()
            paint.color = Color.parseColor("#66FFFFFF")
            canvas.drawCircle(cx, cy, r * 0.55f, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            return host.handleTouch(event)
        }
    }

    companion object {
        private const val TAG = "DragZoneView"
        private const val AUTO_LOCK_MS = 15_000L
    }
}
