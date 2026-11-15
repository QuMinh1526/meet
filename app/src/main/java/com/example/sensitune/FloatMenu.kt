package com.example.sensitune

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Floating bubble + two-tab panel shared by the sensitivity engine and the HUD.
 * Plain views only (no Compose/Fragment/LifecycleOwner), hosted by SensiService.
 *
 * No sliders: while the engine is ACTIVE every drag is scaled, so the panel only
 * offers -/+ buttons and switches. Tapping the bubble opens the panel; the panel
 * closes after 5 seconds without touch.
 */
class FloatMenu(
    private val ctx: Context,
    private val prefs: Prefs,
    private val cb: Callbacks
) {

    interface Callbacks {
        fun onSensitivityChanged()
        fun onEmergencyRelease()
        fun onHudPrefsChanged()
        fun onHudLockChanged()
        fun onHudMove(dx: Int, dy: Int)
        fun engineActive(): Boolean
    }

    private val TAG = "FloatMenu"
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleAttached = false

    private var panel: TouchFrameLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelContent: LinearLayout? = null
    private var panelAttached = false

    private var tab = 0 // 0 = sensitivity, 1 = HUD

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragged = false

    private val closeRunnable = Runnable {
        if (panelAttached) {
            LogBuffer.info(TAG, "panel auto-closed after 5s")
            closePanel()
        }
    }

    fun isShowing(): Boolean = bubbleAttached

    fun show() {
        if (bubbleAttached) return
        if (!OverlayUi.canDraw(ctx)) {
            LogBuffer.warn(TAG, "floating menu not shown: overlay permission missing")
            return
        }
        attachBubble()
    }

    fun hide() {
        handler.removeCallbacks(closeRunnable)
        closePanel()
        detachBubble()
        bubble = null
        bubbleParams = null
    }

    fun onConfigurationChanged() {
        closePanel()
        val p = bubbleParams ?: return
        val size = OverlayUi.screenSize(ctx)
        p.x = clamp(p.x, bubble?.width ?: 0, size.x)
        p.y = clamp(p.y, bubble?.height ?: 0, size.y)
        updateBubbleLayout()
        saveBubblePosition()
    }

    // ------------------------------------------------------------------
    // Bubble
    // ------------------------------------------------------------------

    private fun attachBubble() {
        val size = OverlayUi.screenSize(ctx)
        val bubbleSize = OverlayUi.dp(ctx, 48f)
        val view = TextView(ctx).apply {
            text = "≡"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = OverlayUi.rounded(Color.parseColor("#DD263238"), 24f, ctx)
            alpha = 0.9f
            contentDescription = "SensiTune menu"
            setOnTouchListener { _, ev -> handleBubbleTouch(ev) }
        }
        val p = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        // Android 12 untrusted-touch rule: touchable overlays must stay <= 0.8.
        p.alpha = 0.8f
        val sx = prefs.menuX()
        val sy = prefs.menuY()
        p.x = if (sx >= 0) clamp(sx, bubbleSize, size.x) else size.x - bubbleSize - OverlayUi.dp(ctx, 8f)
        p.y = if (sy >= 0) clamp(sy, bubbleSize, size.y) else size.y / 3

        bubble = view
        bubbleParams = p
        try {
            wm.addView(view, p)
            bubbleAttached = true
            LogBuffer.info(TAG, "bubble attached at (${p.x}, ${p.y})")
            saveBubblePosition()
        } catch (t: Throwable) {
            bubbleAttached = false
            LogBuffer.error(TAG, "addView(bubble) failed", t)
        }
    }

    private fun detachBubble() {
        val v = bubble ?: return
        if (!bubbleAttached) return
        try {
            wm.removeViewImmediate(v)
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "removeView(bubble) failed", t)
        } finally {
            bubbleAttached = false
        }
    }

    private fun handleBubbleTouch(ev: MotionEvent): Boolean {
        val p = bubbleParams ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                startX = p.x
                startY = p.y
                dragged = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (ev.rawX - downRawX).toInt()
                val dy = (ev.rawY - downRawY).toInt()
                if (abs(dx) > OverlayUi.dp(ctx, 6f) || abs(dy) > OverlayUi.dp(ctx, 6f)) {
                    dragged = true
                }
                if (dragged) {
                    val size = OverlayUi.screenSize(ctx)
                    p.x = clamp(startX + dx, bubble?.width ?: 0, size.x)
                    p.y = clamp(startY + dy, bubble?.height ?: 0, size.y)
                    updateBubbleLayout()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragged) {
                    snapToEdge()
                    saveBubblePosition()
                    LogBuffer.info(TAG, "bubble moved to (${p.x}, ${p.y})")
                } else if (ev.actionMasked == MotionEvent.ACTION_UP) {
                    togglePanel()
                }
            }
        }
        return true
    }

    private fun snapToEdge() {
        val p = bubbleParams ?: return
        val size = OverlayUi.screenSize(ctx)
        val w = bubble?.width ?: 0
        val margin = OverlayUi.dp(ctx, 8f)
        p.x = if (p.x + w / 2 < size.x / 2) margin else max(margin, size.x - w - margin)
        updateBubbleLayout()
    }

    private fun updateBubbleLayout() {
        val v = bubble ?: return
        val p = bubbleParams ?: return
        if (!bubbleAttached) return
        try {
            wm.updateViewLayout(v, p)
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "updateViewLayout(bubble) failed", t)
        }
    }

    private fun saveBubblePosition() {
        val p = bubbleParams ?: return
        prefs.setMenuPos(p.x, p.y)
    }

    // ------------------------------------------------------------------
    // Panel
    // ------------------------------------------------------------------

    private fun togglePanel() {
        if (panelAttached) closePanel() else openPanel()
    }

    private fun openPanel() {
        if (panelAttached) return
        if (!OverlayUi.canDraw(ctx)) {
            LogBuffer.warn(TAG, "panel not shown: overlay permission missing")
            return
        }
        val size = OverlayUi.screenSize(ctx)
        val width = min(OverlayUi.dp(ctx, 320f), size.x - OverlayUi.dp(ctx, 24f))
        val frame = TouchFrameLayout(ctx).apply {
            background = OverlayUi.rounded(Color.parseColor("#F2263238"), 14f, ctx)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            onInteract = { resetAutoClose() }
        }
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(
            column,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val titleRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        titleRow.addView(
            label("SensiTune", 15f, Color.WHITE),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        titleRow.addView(smallButton("✕") { closePanel() })
        column.addView(titleRow)

        val tabRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        tabRow.addView(tabButton("Độ nhạy", 0), weight())
        tabRow.addView(tabButton("HUD", 1), weight())
        column.addView(tabRow)

        val scroll = ScrollView(ctx)
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        column.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                min(size.y * 6 / 10, OverlayUi.dp(ctx, 460f))
            )
        )
        panelContent = content

        val p = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.alpha = 0.8f
        val bp = bubbleParams
        if (bp != null) {
            p.x = if (bp.x < size.x / 2) bp.x + (bubble?.width ?: 0) + dp(8f)
            else max(dp(8f), bp.x - width - dp(8f))
            p.y = bp.y
        } else {
            p.x = dp(16f)
            p.y = dp(120f)
        }
        p.x = clamp(p.x, width, size.x)
        p.y = clamp(p.y, OverlayUi.dp(ctx, 200f), size.y)

        panel = frame
        panelParams = p
        buildContent()
        try {
            wm.addView(frame, p)
            panelAttached = true
            LogBuffer.info(TAG, "panel attached tab=$tab")
            resetAutoClose()
        } catch (t: Throwable) {
            panelAttached = false
            panel = null
            panelContent = null
            LogBuffer.error(TAG, "addView(panel) failed", t)
        }
    }

    private fun closePanel() {
        handler.removeCallbacks(closeRunnable)
        val v = panel ?: return
        if (!panelAttached) return
        try {
            wm.removeViewImmediate(v)
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "removeView(panel) failed", t)
        } finally {
            panelAttached = false
            panel = null
            panelParams = null
            panelContent = null
        }
    }

    private fun resetAutoClose() {
        handler.removeCallbacks(closeRunnable)
        handler.postDelayed(closeRunnable, AUTO_CLOSE_MS)
    }

    // ------------------------------------------------------------------
    // Panel content
    // ------------------------------------------------------------------

    private fun buildContent() {
        val box = panelContent ?: return
        box.removeAllViews()
        if (tab == 0) buildSensitivityTab(box) else buildHudTab(box)
    }

    private fun buildSensitivityTab(box: LinearLayout) {
        if (cb.engineActive()) {
            box.addView(
                label(
                    "⚠ Đang bật Độ nhạy: kéo có thể lệch. Nên tạm tắt hoặc dùng hệ số 1.0 khi chỉnh.\n" +
                        "Menu dùng nút -/+ nên không bị ảnh hưởng.",
                    10f, Color.parseColor("#FFAB91")
                )
            )
        }

        box.addView(factorRow("Hệ số X", true) { prefs.factorX })
        box.addView(factorRow("Hệ số Y", false) { prefs.factorY })

        box.addView(
            Switch(ctx).apply {
                text = "Khóa X = Y"
                textSize = 13f
                setTextColor(Color.WHITE)
                isChecked = prefs.lockXY
                setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                    prefs.lockXY = checked
                    if (checked) {
                        prefs.factorY = prefs.factorX
                        cb.onSensitivityChanged()
                        buildContent()
                    }
                    resetAutoClose()
                }
            }
        )

        box.addView(
            label("Bước 0.05 · phạm vi 0.50 – 10.00 · 1.00 = giữ nguyên", 10f, Color.LTGRAY)
        )

        box.addView(
            Button(ctx).apply {
                text = "NHẢ CẢM ỨNG NGAY"
                textSize = 13f
                setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(Color.parseColor("#D32F2F"))
                setOnClickListener {
                    LogBuffer.warn(TAG, "emergency release from floating menu")
                    cb.onEmergencyRelease()
                    closePanel()
                }
            },
            topMargin()
        )
    }

    private fun buildHudTab(box: LinearLayout) {
        box.addView(
            Switch(ctx).apply {
                text = "Hiện HUD"
                textSize = 13f
                setTextColor(Color.WHITE)
                isChecked = prefs.hudEnabled
                setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                    prefs.hudEnabled = checked
                    LogBuffer.info(TAG, "hudEnabled -> $checked")
                    cb.onHudPrefsChanged()
                    resetAutoClose()
                }
            }
        )

        box.addView(
            Switch(ctx).apply {
                text = "Hiện HUD ngày giờ (Fri Jun 7 12:59:59)"
                textSize = 13f
                setTextColor(Color.WHITE)
                isChecked = prefs.clockHudEnabled
                setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                    prefs.clockHudEnabled = checked
                    LogBuffer.info(TAG, "clockHudEnabled -> $checked")
                    cb.onHudPrefsChanged()
                    resetAutoClose()
                }
            },
            topMargin()
        )

        box.addView(
            Button(ctx).apply {
                text = if (prefs.hudLocked) "🔒 Đang khóa vị trí — mở khóa" else "🔓 Đang mở khóa — khóa lại"
                textSize = 12f
                setOnClickListener {
                    prefs.hudLocked = !prefs.hudLocked
                    cb.onHudLockChanged()
                    buildContent()
                }
            },
            topMargin()
        )

        if (!prefs.hudLocked) {
            box.addView(
                label(
                    "Đang bật Độ nhạy: kéo HUD có thể lệch, nên tạm tắt hoặc dùng hệ số 1.0. " +
                        "Nút mũi tên bên dưới chỉnh chính xác 8px/lần.",
                    10f, Color.parseColor("#FFAB91")
                )
            )
            val arrows = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            arrows.addView(smallButton("↑") { cb.onHudMove(0, -8); resetAutoClose() })
            arrows.addView(smallButton("↓") { cb.onHudMove(0, 8); resetAutoClose() })
            arrows.addView(smallButton("←") { cb.onHudMove(-8, 0); resetAutoClose() })
            arrows.addView(smallButton("→") { cb.onHudMove(8, 0); resetAutoClose() })
            box.addView(arrows)
        }

        val orientationRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        orientationRow.addView(
            Button(ctx).apply {
                text = if (prefs.hudVertical) "Kiểu: cột dọc" else "Kiểu: hàng ngang"
                textSize = 12f
                setOnClickListener {
                    prefs.hudVertical = !prefs.hudVertical
                    cb.onHudPrefsChanged()
                    buildContent()
                }
            },
            weight()
        )
        box.addView(orientationRow)

        box.addView(stepperRow("Cỡ chữ (cả 2 HUD)", "%.2f".format(prefs.hudTextScale)) {
            prefs.hudTextScale = (prefs.hudTextScale + it).coerceIn(0.7f, 1.8f)
            cb.onHudPrefsChanged()
            buildContent()
        })
        box.addView(stepperRow("Độ mờ", "%.2f".format(prefs.hudAlpha)) {
            prefs.hudAlpha = (prefs.hudAlpha + it).coerceIn(0.2f, 0.8f)
            cb.onHudPrefsChanged()
            buildContent()
        })
        box.addView(stepperRow("Ngưỡng vàng", "%.0f°C".format(prefs.tempWarnC)) {
            prefs.tempWarnC = (prefs.tempWarnC + it * 10f).coerceIn(30f, 90f)
            cb.onHudPrefsChanged()
            buildContent()
        })
        box.addView(stepperRow("Ngưỡng đỏ", "%.0f°C".format(prefs.tempCritC)) {
            prefs.tempCritC = (prefs.tempCritC + it * 10f).coerceIn(35f, 100f)
            cb.onHudPrefsChanged()
            buildContent()
        })

        box.addView(label("Chỉ số (bật/tắt, đổi thứ tự bằng ↑/↓)", 11f, Color.LTGRAY))

        val metrics = prefs.hudMetrics()
        for ((index, entry) in metrics.withIndex()) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val metric = OverlayUi.metric(entry.first)
            row.addView(
                Switch(ctx).apply {
                    text = metric?.label ?: entry.first
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    isChecked = entry.second
                    setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                        val current = prefs.hudMetrics().toMutableList()
                        if (index in current.indices) {
                            current[index] = Pair(current[index].first, checked)
                            prefs.setHudMetrics(current)
                        }
                        cb.onHudPrefsChanged()
                        resetAutoClose()
                    }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(smallButton("↑") {
                reorder(index, -1)
            })
            row.addView(smallButton("↓") {
                reorder(index, 1)
            })
            box.addView(row)
        }

        box.addView(
            label(
                "FPS đọc từ SurfaceFlinger (dumpsys --latency, hiệu ActualPresentTime của layer app đang chạy). " +
                    "HUD ngày giờ là cửa sổ riêng, không chung với nhiệt độ/CPU/GPU.",
                9f, Color.LTGRAY
            )
        )
    }

    private fun reorder(index: Int, delta: Int) {
        val list = prefs.hudMetrics().toMutableList()
        val target = index + delta
        if (index !in list.indices || target !in list.indices) return
        val item = list.removeAt(index)
        list.add(target, item)
        prefs.setHudMetrics(list)
        cb.onHudPrefsChanged()
        buildContent()
    }

    private fun factorRow(title: String, isX: Boolean, value: () -> Float): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            label(title, 13f, Color.WHITE),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(smallButton("−") { adjust(isX, -0.05f) })
        row.addView(label("%.2f".format(value()), 15f, Color.WHITE))
        row.addView(smallButton("+") { adjust(isX, 0.05f) })
        return row
    }

    private fun stepperRow(title: String, current: String, onChange: (Float) -> Unit): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            label(title, 12f, Color.WHITE),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(smallButton("−") { onChange(-0.1f) })
        row.addView(label(current, 12f, Color.WHITE))
        row.addView(smallButton("+") { onChange(0.1f) })
        return row
    }

    private fun adjust(isX: Boolean, delta: Float) {
        val base = if (isX) prefs.factorX else prefs.factorY
        val v = quantize(base + delta)
        if (prefs.lockXY) {
            prefs.factorX = v
            prefs.factorY = v
        } else if (isX) {
            prefs.factorX = v
        } else {
            prefs.factorY = v
        }
        LogBuffer.info(TAG, "factor ${if (isX) "X" else "Y"} -> $v (lock=${prefs.lockXY})")
        cb.onSensitivityChanged()
        buildContent()
    }

    private fun quantize(v: Float): Float =
        ((v * 20f).roundToInt() / 20f).coerceIn(0.5f, 10f)

    // ------------------------------------------------------------------
    // Small view helpers
    // ------------------------------------------------------------------

    private fun label(text: String, size: Float, color: Int): TextView = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        setPadding(dp(2f), dp(4f), dp(2f), dp(4f))
    }

    private fun smallButton(text: String, onClick: () -> Unit): Button = Button(ctx).apply {
        this.text = text
        textSize = 12f
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
        setOnClickListener {
            resetAutoClose()
            onClick()
        }
    }

    private fun tabButton(text: String, index: Int): Button = Button(ctx).apply {
        this.text = text
        textSize = 12f
        backgroundTintList = ColorStateList.valueOf(
            if (tab == index) Color.parseColor("#455A64") else Color.parseColor("#263238")
        )
        setTextColor(Color.WHITE)
        setOnClickListener {
            tab = index
            buildContent()
            resetAutoClose()
        }
    }

    private fun weight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun topMargin(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6f) }

    private fun dp(v: Float): Int = OverlayUi.dp(ctx, v)

    private fun clamp(v: Int, content: Int, screen: Int): Int {
        val margin = dp(4f)
        return max(0, min(v, max(0, screen - max(content, dp(48f)) - margin)))
    }

    companion object {
        private const val AUTO_CLOSE_MS = 5000L
    }
}
