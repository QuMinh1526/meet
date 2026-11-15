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
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Performance HUD overlay. Plain views only, always driven from the main thread.
 *
 * Locked (default): FLAG_NOT_TOUCHABLE, so every touch goes straight through to
 * the game and the HUD can never be dragged by accident. Unlocked: draggable,
 * highlighted, auto-locks after 15 s of inactivity (or long-press for 1 s).
 */
class PerfHud(private val ctx: Context, private val prefs: Prefs) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var root: LinearLayout? = null
    private var cellsRow: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private val cells = LinkedHashMap<String, TextView>()

    private var attached = false
    private var locked = true
    private var engineActive = false
    private var lastJson: JSONObject? = null

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false

    private val autoLock = Runnable { if (attached && !locked) setLocked(true) }
    private val longPressLock = Runnable {
        if (attached && !locked && !moved) {
            LogBuffer.info(TAG, "long press on HUD -> locking")
            setLocked(true)
        }
    }

    fun isShowing(): Boolean = attached

    fun show() {
        if (attached) {
            applyPrefs()
            return
        }
        if (!OverlayUi.canDraw(ctx)) {
            LogBuffer.warn(TAG, "HUD not shown: overlay permission missing")
            return
        }
        locked = prefs.hudLocked
        buildView()
        attach()
    }

    fun hide() {
        handler.removeCallbacks(autoLock)
        handler.removeCallbacks(longPressLock)
        detach()
        root = null
        cellsRow = null
        cells.clear()
        params = null
        lastJson = null
    }

    fun setEngineActive(active: Boolean) {
        engineActive = active
        updateWarning()
    }

    /** Rebuilds labels/order/composition after the menu changed a preference. */
    fun applyPrefs() {
        if (!attached) {
            locked = prefs.hudLocked
            return
        }
        val oldX = params?.x ?: 0
        val oldY = params?.y ?: 0
        handler.removeCallbacks(autoLock)
        detach()
        locked = prefs.hudLocked
        buildView()
        params?.x = oldX
        params?.y = oldY
        attach()
        lastJson?.let { update(it) }
    }

    fun setLocked(value: Boolean) {
        locked = value
        prefs.hudLocked = value
        handler.removeCallbacks(autoLock)
        handler.removeCallbacks(longPressLock)
        applyWindowFlags()
        updateChrome()
        if (!value) {
            LogBuffer.info(TAG, "HUD unlocked (drag enabled)")
            handler.postDelayed(autoLock, AUTO_LOCK_MS)
        } else {
            savePosition()
            LogBuffer.info(TAG, "HUD locked (touch-through)")
        }
    }

    fun toggleLock() = setLocked(!locked)

    fun moveBy(dx: Int, dy: Int) {
        val p = params ?: return
        val size = OverlayUi.screenSize(ctx)
        val w = root?.width ?: 0
        val h = root?.height ?: 0
        p.x = clampX(p.x + dx, w, size.x)
        p.y = clampY(p.y + dy, h, size.y)
        updateLayout()
        savePosition()
    }

    /** Called after a rotation: load the saved position for the new orientation. */
    fun onConfigurationChanged() {
        val p = params ?: return
        val landscape = OverlayUi.landscape(ctx)
        val size = OverlayUi.screenSize(ctx)
        val w = root?.width ?: 0
        val h = root?.height ?: 0
        val sx = prefs.hudX(landscape)
        val sy = prefs.hudY(landscape)
        p.x = if (sx >= 0) sx else p.x
        p.y = if (sy >= 0) sy else p.y
        p.x = clampX(p.x, w, size.x)
        p.y = clampY(p.y, h, size.y)
        updateLayout()
        savePosition()
    }

    fun update(json: JSONObject) {
        lastJson = json
        for ((key, view) in cells) {
            val (text, temp) = format(key, json)
            view.text = text
            val metric = OverlayUi.metric(key)
            view.setTextColor(
                if (metric?.temperature == true) OverlayUi.tempColor(ctx, prefs, temp)
                else Color.WHITE
            )
        }
    }

    // ------------------------------------------------------------------
    // View construction
    // ------------------------------------------------------------------

    private fun buildView() {
        val scale = prefs.hudTextScale
        cells.clear()
        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            background = OverlayUi.rounded(
                Color.parseColor("#CC000000"), 10f, ctx,
                if (!locked) Color.parseColor("#FFC107") else null
            )
        }

        val header = TextView(ctx).apply {
            text = "🔓 mở khóa — kéo để di chuyển"
            textSize = 10f * scale
            setTextColor(Color.parseColor("#FFD54F"))
            id = View.generateViewId()
            tag = TAG_HEADER
            visibility = if (locked) View.GONE else View.VISIBLE
        }
        outer.addView(header)

        val row = LinearLayout(ctx).apply {
            orientation = if (prefs.hudVertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        cellsRow = row
        outer.addView(row)

        var any = false
        for ((key, on) in prefs.hudMetrics()) {
            if (!on) continue
            val metric = OverlayUi.metric(key) ?: continue
            val tv = TextView(ctx).apply {
                text = "${metric.label} --"
                textSize = 13f * scale
                setTextColor(Color.WHITE)
                setPadding(dp(4f), dp(2f), dp(6f), dp(2f))
                isSingleLine = true
            }
            cells[key] = tv
            row.addView(tv)
            any = true
        }
        if (!any) {
            row.addView(TextView(ctx).apply {
                text = "HUD: chưa chọn chỉ số"
                textSize = 12f * scale
                setTextColor(Color.LTGRAY)
            })
        }

        val warn = TextView(ctx).apply {
            text = "⚠ Đang bật Độ nhạy: kéo có thể lệch. Nên tạm tắt hoặc dùng hệ số 1.0 khi chỉnh vị trí. " +
                "Dùng nút mũi tên trong menu để chỉnh chính xác."
            textSize = 9f * scale
            setTextColor(Color.parseColor("#FFAB91"))
            tag = TAG_WARN
            maxWidth = dp(240f)
            visibility = View.GONE
        }
        outer.addView(warn)

        root = outer
        outer.setOnTouchListener { _, ev -> handleTouch(ev) }
        params = buildParams()
        updateWarning()
    }

    private fun buildParams(): WindowManager.LayoutParams {
        val landscape = OverlayUi.landscape(ctx)
        val size = OverlayUi.screenSize(ctx)
        val savedX = prefs.hudX(landscape)
        val savedY = prefs.hudY(landscape)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags(),
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = if (savedX >= 0) savedX else dp(12f)
        p.y = if (savedY >= 0) savedY else clampY(dp(96f), 0, size.y)
        p.alpha = prefs.hudAlpha
        return p
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
        // Border + open-lock hint change with the lock state.
        v.background = OverlayUi.rounded(
            Color.parseColor("#CC000000"), 10f, ctx,
            if (!locked) Color.parseColor("#FFC107") else null
        )
        if (attached) updateLayout()
    }

    private fun updateChrome() {
        root?.findViewWithTag<TextView>(TAG_HEADER)?.visibility =
            if (locked) View.GONE else View.VISIBLE
        updateWarning()
    }

    private fun updateWarning() {
        val show = engineActive && !locked
        root?.findViewWithTag<TextView>(TAG_WARN)?.visibility =
            if (show) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------
    // Window attach / detach
    // ------------------------------------------------------------------

    private fun attach() {
        val v = root ?: return
        val p = params ?: return
        if (attached) return
        try {
            wm.addView(v, p)
            attached = true
            LogBuffer.info(TAG, "HUD attached at (${p.x}, ${p.y}) locked=$locked")
            if (!locked) handler.postDelayed(autoLock, AUTO_LOCK_MS)
            v.post {
                if (attached) {
                    clampCurrent()
                    savePosition()
                }
            }
        } catch (t: Throwable) {
            attached = false
            LogBuffer.error(TAG, "addView(HUD) failed", t)
        }
    }

    private fun detach() {
        val v = root ?: return
        if (!attached) return
        try {
            wm.removeViewImmediate(v)
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "removeView(HUD) failed", t)
        } finally {
            attached = false
        }
    }

    private fun updateLayout() {
        val v = root ?: return
        val p = params ?: return
        if (!attached) return
        try {
            wm.updateViewLayout(v, p)
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "updateViewLayout(HUD) failed", t)
        }
    }

    private fun clampCurrent() {
        val p = params ?: return
        val size = OverlayUi.screenSize(ctx)
        p.x = clampX(p.x, root?.width ?: 0, size.x)
        p.y = clampY(p.y, root?.height ?: 0, size.y)
        updateLayout()
    }

    private fun clampX(x: Int, width: Int, screenW: Int): Int {
        val margin = dp(4f)
        return max(0, min(x, max(0, screenW - max(width, dp(48f)) - margin)))
    }

    private fun clampY(y: Int, height: Int, screenH: Int): Int {
        val margin = dp(4f)
        return max(0, min(y, max(0, screenH - max(height, dp(32f)) - margin)))
    }

    private fun savePosition() {
        val p = params ?: return
        prefs.setHudPos(p.x, p.y, OverlayUi.landscape(ctx))
    }

    // ------------------------------------------------------------------
    // Drag + lock gestures
    // ------------------------------------------------------------------

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
                    LogBuffer.info(TAG, "HUD moved to (${p.x}, ${p.y})")
                }
                if (!locked) handler.postDelayed(autoLock, AUTO_LOCK_MS)
            }
        }
        return true
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    private fun format(key: String, o: JSONObject): Pair<String, Double?> {
        val errors = o.optJSONArray("errors")
        val failed = errors?.let { arr ->
            (0 until arr.length()).any { arr.optString(it) == keyName(key) }
        } ?: false
        fun d(name: String): Double? {
            if (failed) return null
            if (!o.has(name) || o.isNull(name)) return null
            val v = o.optDouble(name, Double.NaN)
            return if (v.isNaN()) null else v
        }
        return when (key) {
            "fps" -> d("fps")?.let { Pair("FPS ~${it.toInt()}", null) } ?: Pair("FPS --", null)
            "cpu" -> temp("CPU", d("cpuTemp"))
            "gpu" -> temp("GPU", d("gpuTemp"))
            "battery" -> temp("Pin", d("batteryTemp"))
            "ram" -> temp("RAM", d("ramTemp"))
            "skin" -> temp("Thân", d("skinTemp"))
            "shell" -> temp("Lưng", d("shellTemp"))
            "cpuPct" -> d("cpuPct")?.let { Pair("CPU ${it.toInt()}%", null) } ?: Pair("CPU --", null)
            "cpuFreq" -> d("cpuFreqMhz")?.let {
                val text = if (it >= 1000) "CPU %.2fGHz".format(it / 1000.0) else "CPU ${it.toInt()}MHz"
                Pair(text, null)
            } ?: Pair("CPU --", null)
            else -> Pair("--", null)
        }
    }

    private fun temp(label: String, value: Double?): Pair<String, Double?> =
        if (value == null) Pair("$label --", null)
        else Pair("$label %.1f°C".format(value), value)

    /** JSON key used in the sampler's "errors" array. */
    private fun keyName(key: String): String = when (key) {
        "cpu" -> "cpuTemp"
        "gpu" -> "gpuTemp"
        "battery" -> "batteryTemp"
        "ram" -> "ramTemp"
        "skin" -> "skinTemp"
        "shell" -> "shellTemp"
        "cpuFreq" -> "cpuFreq"
        else -> key
    }

    private fun dp(v: Float): Int = OverlayUi.dp(ctx, v)

    companion object {
        private const val TAG = "PerfHud"
        private const val TAG_HEADER = "hud_header"
        private const val TAG_WARN = "hud_warn"
        private const val AUTO_LOCK_MS = 15_000L
    }
}
