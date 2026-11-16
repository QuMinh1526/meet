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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.sensitune.ui.kit.ColorDot
import com.example.sensitune.ui.kit.IconButton
import com.example.sensitune.ui.kit.Kit
import com.example.sensitune.ui.kit.KitReleasable
import com.example.sensitune.ui.kit.LabeledSlider
import com.example.sensitune.ui.kit.SectionCard
import com.example.sensitune.ui.kit.Stepper
import com.example.sensitune.ui.kit.TabBar
import com.example.sensitune.ui.kit.ToggleRow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Floating bubble + two-tab panel shared by the sensitivity engine and the HUD.
 * Plain views only (ui.kit widgets, no Compose/Fragment/LifecycleOwner).
 *
 * The panel uses real sliders. While it is open the engine is told to suspend
 * scaling (k=1.0, no soft recenter) but to KEEP the grab, so the handles track the
 * finger instead of being multiplied. Suspension is renewed every ~2s and the
 * engine also fails safe after 10s without renewal.
 *
 * The Context is wrapped in the dark overlay theme so Switch/SeekBar render
 * correctly inside a WindowManager window.
 */
class FloatMenu(
    appContext: Context,
    private val prefs: Prefs,
    private val cb: Callbacks
) {

    interface Callbacks {
        /** Coalesced, debounced preview while dragging (never on the main thread). */
        fun onFactorPreview(x: Float, y: Float)

        /** Final value when the finger leaves the slider. */
        fun onFactorCommit(x: Float, y: Float)

        fun onSensitivitySettingsChanged()
        fun onPanelOpenChanged(open: Boolean)
        fun onEmergencyRelease()
        fun onHudPrefsChanged()
        fun onHudLockChanged()
        fun onHudMove(dx: Int, dy: Int)
        fun engineActive(): Boolean
        fun factorSendFailed()
    }

    private val TAG = "FloatMenu"
    private val ctx: Context = Kit.overlayTheme(appContext)
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
    private var statusDot: ColorDot? = null
    private val kitWidgets = ArrayList<KitReleasable>()

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

    /** Called by SensiService when a slider value could not be delivered. */
    fun notifyFactorSendFailed() {
        statusDot?.setError("Không gửi được hệ số tới engine")
    }

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
        val width = min(OverlayUi.dp(ctx, 340f), size.x - OverlayUi.dp(ctx, 24f))
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

        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            label("SensiTune", 15f, Color.WHITE),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        titleRow.addView(IconButton(ctx, "✕") { closePanel() })
        column.addView(titleRow)

        val tabBar = TabBar(ctx, listOf("Độ nhạy", "HUD")) { index ->
            tab = index
            buildContent()
            resetAutoClose()
        }
        tabBar.select(tab, notify = false)
        register(tabBar)
        column.addView(
            tabBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6f) }
        )

        val scroll = ScrollView(ctx)
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2f), 0, dp(2f))
        }
        scroll.addView(content)
        column.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                min(size.y * 6 / 10, OverlayUi.dp(ctx, 500f))
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
            // Slider handle must not be scaled while it is being dragged.
            cb.onPanelOpenChanged(true)
        } catch (t: Throwable) {
            panelAttached = false
            panel = null
            panelContent = null
            LogBuffer.error(TAG, "addView(panel) failed", t)
            cb.onPanelOpenChanged(false)
        }
    }

    private fun closePanel() {
        handler.removeCallbacks(closeRunnable)
        val v = panel ?: return
        if (!panelAttached) return
        val wasAttached = panelAttached
        try {
            wm.removeViewImmediate(v)
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "removeView(panel) failed", t)
        } finally {
            releasePanelWidgets()
            panelAttached = false
            panel = null
            panelParams = null
            panelContent = null
            statusDot = null
        }
        if (wasAttached) cb.onPanelOpenChanged(false)
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
        releasePanelWidgets()
        box.removeAllViews()
        if (tab == 0) buildSensitivityTab(box) else buildHudTab(box)
    }

    private fun buildSensitivityTab(box: LinearLayout) {
        if (cb.engineActive()) {
            box.addView(
                label(
                    "Đang bật engine: bảng này tạm ngưng nhân hệ số (vẫn grab) để slider bám ngón. " +
                        "Đóng bảng là áp dụng lại ngay.",
                    10f, Color.parseColor("#FFCC80")
                )
            )
        }

        val statusRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = ColorDot(ctx, Kit.neutral())
        statusRow.addView(dot)
        statusRow.addView(
            label("Chấm đỏ = không gửi được giá trị tới engine", 9f, Color.LTGRAY),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        box.addView(statusRow)
        statusDot = dot

        val factorCard = SectionCard(ctx, "Độ nhạy (X = ngang màn hình, Y = dọc màn hình)")
        val sliderX = LabeledSlider(ctx, "Hệ số X", 0.50f, 2.50f, 0.05f, prefs.factorX)
        val sliderY = LabeledSlider(ctx, "Hệ số Y", 0.50f, 2.50f, 0.05f, prefs.factorY)

        sliderX.onChange = { value, fromUser ->
            if (fromUser) {
                statusDot?.clear()
                prefs.factorX = value
                if (prefs.lockXY) {
                    prefs.factorY = value
                    sliderY.setValue(value, notify = false)
                }
                cb.onFactorPreview(prefs.factorX, prefs.factorY)
                resetAutoClose()
            }
        }
        sliderX.onStopTracking = { value ->
            prefs.factorX = value
            if (prefs.lockXY) prefs.factorY = value
            cb.onFactorCommit(prefs.factorX, prefs.factorY)
            resetAutoClose()
        }
        sliderY.onChange = { value, fromUser ->
            if (fromUser) {
                statusDot?.clear()
                prefs.factorY = value
                if (prefs.lockXY) {
                    prefs.factorX = value
                    sliderX.setValue(value, notify = false)
                }
                cb.onFactorPreview(prefs.factorX, prefs.factorY)
                resetAutoClose()
            }
        }
        sliderY.onStopTracking = { value ->
            prefs.factorY = value
            if (prefs.lockXY) prefs.factorX = value
            cb.onFactorCommit(prefs.factorX, prefs.factorY)
            resetAutoClose()
        }
        register(sliderX)
        register(sliderY)
        factorCard.addRow(sliderX)
        factorCard.addRow(sliderY)
        factorCard.addRow(
            ToggleRow(ctx, "Khóa X = Y", prefs.lockXY).also { row ->
                register(row)
                row.onCheckedChange = { checked ->
                    prefs.lockXY = checked
                    if (checked) {
                        prefs.factorY = prefs.factorX
                        sliderY.setValue(prefs.factorX, notify = false)
                        cb.onFactorCommit(prefs.factorX, prefs.factorY)
                    }
                    LogBuffer.info(TAG, "lock X=Y -> $checked")
                    resetAutoClose()
                }
            }
        )
        factorCard.addRow(sectionHint("0.50 – 2.50 · bước 0.05 · 1.00 = giữ nguyên"))
        box.addView(factorCard, topMargin())
        register(factorCard)

        val edgeCard = SectionCard(ctx, "Mép màn hình & xoay")
        val marginSlider = LabeledSlider(
            ctx, "Vùng đệm mép (%)", 0f, 25f, 1f, prefs.edgeMarginPct.toFloat()
        ).also { slider ->
            slider.onChange = { value, fromUser ->
                if (fromUser) {
                    prefs.edgeMarginPct = value.roundToInt()
                    cb.onSensitivitySettingsChanged()
                    resetAutoClose()
                }
            }
            slider.onStopTracking = {
                cb.onSensitivitySettingsChanged()
                resetAutoClose()
            }
            register(slider)
        }
        edgeCard.addRow(marginSlider)
        edgeCard.addRow(
            ToggleRow(ctx, "Soft recenter (giảm dần sát mép)", prefs.softRecenter).also { row ->
                register(row)
                row.onCheckedChange = { checked ->
                    prefs.softRecenter = checked
                    cb.onSensitivitySettingsChanged()
                    resetAutoClose()
                }
            }
        )
        val reanchorWarning = sectionHint(
            "⚠ Chạm lại ở giữa: game có thể coi đó là một lần chạm MỚI (tối đa 1 lần/giây)."
        )
        val reanchorRow = ToggleRow(
            ctx, "Chạm lại ở giữa khi chạm mép", prefs.reanchor
        ).also { row ->
            register(row)
            row.onCheckedChange = { checked ->
                prefs.reanchor = checked
                reanchorWarning.visibility = if (checked) View.VISIBLE else View.GONE
                cb.onSensitivitySettingsChanged()
                resetAutoClose()
            }
        }
        edgeCard.addRow(reanchorRow)
        reanchorWarning.visibility = if (prefs.reanchor) View.VISIBLE else View.GONE
        edgeCard.addRow(reanchorWarning)
        edgeCard.addRow(sectionHint("Vùng đệm tính theo % chiều dài từng trục thô (X ngắn hơn Y)."))
        box.addView(edgeCard, topMargin())
        register(edgeCard)

        box.addView(
            Button(ctx).apply {
                text = "NHẢ CẢM ỨNG NGAY"
                textSize = 14f
                setTextColor(Color.WHITE)
                background.setTint(Kit.danger())
                minimumHeight = dp(56f)
                setOnClickListener {
                    LogBuffer.warn(TAG, "emergency release from floating menu")
                    cb.onEmergencyRelease()
                    closePanel()
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56f)
            ).apply { topMargin = dp(10f) }
        )
    }

    private fun buildHudTab(box: LinearLayout) {
        val hudCard = SectionCard(ctx, "HUD")
        hudCard.addRow(
            ToggleRow(ctx, "Hiện HUD", prefs.hudEnabled).also { row ->
                register(row)
                row.onCheckedChange = { checked ->
                    prefs.hudEnabled = checked
                    LogBuffer.info(TAG, "hudEnabled -> $checked")
                    cb.onHudPrefsChanged()
                    resetAutoClose()
                }
            }
        )
        hudCard.addRow(
            ToggleRow(ctx, "HUD ngày giờ (Fri Jun 7 12:59:59)", prefs.clockHudEnabled).also { row ->
                register(row)
                row.onCheckedChange = { checked ->
                    prefs.clockHudEnabled = checked
                    LogBuffer.info(TAG, "clockHudEnabled -> $checked")
                    cb.onHudPrefsChanged()
                    resetAutoClose()
                }
            }
        )
        hudCard.addRow(
            ToggleRow(ctx, "Khóa vị trí HUD", prefs.hudLocked).also { row ->
                register(row)
                row.onCheckedChange = { checked ->
                    prefs.hudLocked = checked
                    cb.onHudLockChanged()
                    buildContent()
                    resetAutoClose()
                }
            }
        )
        box.addView(hudCard, topMargin())
        register(hudCard)

        if (!prefs.hudLocked) {
            val arrowsCard = SectionCard(ctx, "Di chuyển HUD (8px mỗi lần)")
            val arrows = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            arrows.addView(IconButton(ctx, "↑") { cb.onHudMove(0, -8); resetAutoClose() }, arrowParams())
            arrows.addView(IconButton(ctx, "↓") { cb.onHudMove(0, 8); resetAutoClose() }, arrowParams())
            arrows.addView(IconButton(ctx, "←") { cb.onHudMove(-8, 0); resetAutoClose() }, arrowParams())
            arrows.addView(IconButton(ctx, "→") { cb.onHudMove(8, 0); resetAutoClose() }, arrowParams())
            arrowsCard.addRow(arrows)
            box.addView(arrowsCard, topMargin())
            register(arrowsCard)
        }

        val styleCard = SectionCard(ctx, "Kiểu hiển thị")
        styleCard.addRow(
            Stepper(
                ctx,
                "Cỡ chữ (cả 2 HUD)",
                "%.2f".format(prefs.hudTextScale)
            ) { delta ->
                prefs.hudTextScale = (prefs.hudTextScale + delta).coerceIn(0.7f, 1.8f)
                cb.onHudPrefsChanged()
                buildContent()
                resetAutoClose()
            }.also { register(it) }
        )
        styleCard.addRow(
            Stepper(ctx, "Độ mờ", "%.2f".format(prefs.hudAlpha)) { delta ->
                prefs.hudAlpha = (prefs.hudAlpha + delta).coerceIn(0.2f, 0.8f)
                cb.onHudPrefsChanged()
                buildContent()
                resetAutoClose()
            }.also { register(it) }
        )
        styleCard.addRow(
            ToggleRow(ctx, if (prefs.hudVertical) "Kiểu: cột dọc" else "Kiểu: hàng ngang", prefs.hudVertical)
                .also { row ->
                    register(row)
                    row.onCheckedChange = { checked ->
                        prefs.hudVertical = checked
                        cb.onHudPrefsChanged()
                        buildContent()
                        resetAutoClose()
                    }
                }
        )
        styleCard.addRow(
            Stepper(ctx, "Ngưỡng vàng", "%.0f°C".format(prefs.tempWarnC)) { delta ->
                prefs.tempWarnC = (prefs.tempWarnC + delta * 10f).coerceIn(30f, 90f)
                cb.onHudPrefsChanged()
                buildContent()
                resetAutoClose()
            }.also { register(it) }
        )
        styleCard.addRow(
            Stepper(ctx, "Ngưỡng đỏ", "%.0f°C".format(prefs.tempCritC)) { delta ->
                prefs.tempCritC = (prefs.tempCritC + delta * 10f).coerceIn(35f, 100f)
                cb.onHudPrefsChanged()
                buildContent()
                resetAutoClose()
            }.also { register(it) }
        )
        box.addView(styleCard, topMargin())
        register(styleCard)

        val metricsCard = SectionCard(ctx, "Chỉ số (bật/tắt, đổi thứ tự bằng mũi tên)")
        val metrics = prefs.hudMetrics()
        for ((index, entry) in metrics.withIndex()) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val metric = OverlayUi.metric(entry.first)
            row.addView(
                ToggleRow(ctx, metric?.label ?: entry.first, entry.second).also { toggle ->
                    register(toggle)
                    toggle.onCheckedChange = { checked ->
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
            row.addView(IconButton(ctx, "↑") { reorder(index, -1) })
            row.addView(IconButton(ctx, "↓") { reorder(index, 1) })
            metricsCard.addRow(row)
        }
        box.addView(metricsCard, topMargin())
        register(metricsCard)

        box.addView(
            sectionHint(
                "FPS đọc từ SurfaceFlinger (dumpsys --latency, hiệu ActualPresentTime của layer " +
                    "app đang chạy). HUD ngày giờ là cửa sổ riêng."
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

    // ------------------------------------------------------------------
    // Small view helpers
    // ------------------------------------------------------------------

    private fun register(widget: KitReleasable) {
        kitWidgets.add(widget)
    }

    private fun releasePanelWidgets() {
        for (widget in kitWidgets) {
            try {
                widget.release()
            } catch (t: Throwable) {
                LogBuffer.error(TAG, "widget release failed", t)
            }
        }
        kitWidgets.clear()
        statusDot = null
    }

    private fun label(text: String, size: Float, color: Int): TextView = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        setPadding(dp(2f), dp(4f), dp(2f), dp(4f))
    }

    private fun sectionHint(text: String): TextView = label(text, 9f, Color.LTGRAY)

    private fun arrowParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(dp(40f), dp(40f)).apply {
            marginEnd = dp(6f)
        }

    private fun topMargin(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8f) }

    private fun dp(v: Float): Int = OverlayUi.dp(ctx, v)

    private fun clamp(v: Int, content: Int, screen: Int): Int {
        val margin = dp(4f)
        return max(0, min(v, max(0, screen - max(content, dp(48f)) - margin)))
    }

    companion object {
        private const val AUTO_CLOSE_MS = 5000L
    }
}
