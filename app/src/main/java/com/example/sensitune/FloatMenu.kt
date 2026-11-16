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
import android.widget.Switch
import android.widget.TextView
import com.example.sensitune.ui.kit.ColorDot
import com.example.sensitune.ui.kit.CurvePreview
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
 * Floating bubble + three-tab panel:
 *   [Mode hiện tại] [Style] [HUD]
 * The first row always has "Mode ▾", the engine switch and the close button, and
 * the red NHẢ button is visible on every tab.
 *
 * While the panel is open the engine is told to suspend scaling (k=1.0, no soft
 * recenter) but to KEEP the grab, so slider handles track the finger.
 */
class FloatMenu(
    appContext: Context,
    private val prefs: Prefs,
    private val cb: Callbacks
) {

    interface Callbacks {
        fun onFactorPreview(x: Float, y: Float)
        fun onFactorCommit(x: Float, y: Float)
        fun onSensitivitySettingsChanged()
        fun onPanelOpenChanged(open: Boolean)
        fun onEmergencyRelease()
        fun onHudPrefsChanged()
        fun onHudLockChanged()
        fun onHudMove(dx: Int, dy: Int)
        fun engineActive(): Boolean
        fun factorSendFailed()
        fun onModeChanged(mode: Int)
        fun onStyleChanged()
        fun onDragConfigChanged()
        fun onDragZoneChanged()
        fun onEngineToggle(enable: Boolean)
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

    private var tab = 0 // 0 = current mode, 1 = style, 2 = HUD
    private var statusDot: ColorDot? = null
    private var modeButton: TextView? = null
    private var powerSwitch: Switch? = null
    private var tabBar: TabBar? = null
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

    fun notifyFactorSendFailed() {
        statusDot?.setError("Không gửi được giá trị tới engine")
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
        val width = min(OverlayUi.dp(ctx, 360f), size.x - OverlayUi.dp(ctx, 24f))
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

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val modeBtn = TextView(ctx).apply {
            text = modeLabel(prefs.mode) + " ▾"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            minimumHeight = dp(36f)
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            background = OverlayUi.rounded(Color.parseColor("#3345505B"), 8f, ctx)
            contentDescription = "Mode"
        }
        modeButton = modeBtn
        header.addView(modeBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val power = Switch(ctx).apply {
            text = ""
            isChecked = cb.engineActive()
            contentDescription = "Bật tắt engine"
            setOnCheckedChangeListener { _, checked ->
                LogBuffer.info(TAG, "engine switch -> $checked")
                cb.onEngineToggle(checked)
                resetAutoClose()
                handler.postDelayed({ refreshHeader() }, 900)
            }
        }
        powerSwitch = power
        header.addView(power, LinearLayout.LayoutParams(dp(52f), dp(40f)))
        header.addView(IconButton(ctx, "✕") { closePanel() })
        column.addView(header)

        // Real dropdown: tapping "Mode ▾" expands the two choices in-place
        // (no PopupWindow, so no overlay window-token problems).
        val modeMenu = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(4f), 0, dp(2f))
        }
        fun addModeChoice(label: String, value: Int) {
            modeMenu.addView(
                TextView(ctx).apply {
                    text = label
                    setTextColor(if (prefs.mode == value) Kit.accent(ctx) else Color.WHITE)
                    textSize = 13f
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(Kit.MIN_TOUCH_DP)
                    setPadding(dp(10f), 0, dp(10f), 0)
                    background = OverlayUi.rounded(Color.parseColor("#2237454F"), 6f, ctx)
                    setOnClickListener {
                        modeMenu.visibility = View.GONE
                        if (prefs.mode != value) switchMode(value)
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(2f) }
            )
        }
        addModeChoice("Sensitivity — X/Y, vùng, soft recenter", MODE_SENSITIVITY)
        addModeChoice("Drag — vòng tròn + gain theo quãng/tốc độ", MODE_DRAG)
        modeBtn.setOnClickListener {
            val show = modeMenu.visibility != View.VISIBLE
            modeMenu.removeAllViews()
            addModeChoice("Sensitivity — X/Y, vùng, soft recenter", MODE_SENSITIVITY)
            addModeChoice("Drag — vòng tròn + gain theo quãng/tốc độ", MODE_DRAG)
            modeMenu.visibility = if (show) View.VISIBLE else View.GONE
            resetAutoClose()
        }
        column.addView(modeMenu)

        val tabBar = TabBar(ctx, listOf(modeLabel(prefs.mode), "Style", "HUD")) { index ->
            tab = index
            buildContent()
            resetAutoClose()
        }
        tabBar.select(tab.coerceIn(0, 2), notify = false)
        this.tabBar = tabBar
        // Persistent chrome: NOT part of the per-tab widget list, so
        // buildContent() never releases the tab strip.
        column.addView(
            tabBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6f); bottomMargin = dp(4f) }
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
                min(size.y * 5 / 10, OverlayUi.dp(ctx, 460f))
            )
        )
        panelContent = content

        // Red release button is outside the scroll: visible on every tab.
        column.addView(
            Button(ctx).apply {
                text = "NHẢ CẢM ỨNG NGAY"
                textSize = 14f
                setTextColor(Color.WHITE)
                background.setTint(Kit.danger())
                minimumHeight = dp(52f)
                setOnClickListener {
                    LogBuffer.warn(TAG, "emergency release from floating menu")
                    cb.onEmergencyRelease()
                    closePanel()
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52f)
            ).apply { topMargin = dp(8f) }
        )

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
            tabBar?.release()
            tabBar = null
            panelAttached = false
            panel = null
            panelParams = null
            panelContent = null
            statusDot = null
            modeButton = null
            powerSwitch = null
            tabBar = null
        }
        if (wasAttached) cb.onPanelOpenChanged(false)
    }

    private fun resetAutoClose() {
        handler.removeCallbacks(closeRunnable)
        handler.postDelayed(closeRunnable, AUTO_CLOSE_MS)
    }

    private fun switchMode(mode: Int) {
        prefs.mode = mode
        cb.onModeChanged(mode)
        tab = 0
        modeButton?.text = modeLabel(mode) + " ▾"
        tabBar?.setTitle(0, modeLabel(mode))
        buildContent()
        resetAutoClose()
        LogBuffer.info(TAG, "mode -> ${modeLabel(mode)}")
    }

    private fun refreshHeader() {
        powerSwitch?.isChecked = cb.engineActive()
        modeButton?.text = modeLabel(prefs.mode) + " ▾"
    }

    // ------------------------------------------------------------------
    // Panel content
    // ------------------------------------------------------------------

    private fun buildContent() {
        val box = panelContent ?: return
        releasePanelWidgets()
        box.removeAllViews()
        when (tab) {
            1 -> buildStyleTab(box)
            2 -> buildHudTab(box)
            else -> {
                if (prefs.mode == MODE_DRAG) buildDragTab(box) else buildSensitivityTab(box)
            }
        }
    }

    private fun buildSensitivityTab(box: LinearLayout) {
        val factorCard = SectionCard(ctx, "Độ nhạy (X = ngang màn hình, Y = dọc màn hình)")
        val sliderX = LabeledSlider(ctx, "Hệ số X", 0.50f, 2.50f, 0.05f, prefs.factorX)
        val sliderY = LabeledSlider(ctx, "Hệ số Y", 0.50f, 2.50f, 0.05f, prefs.factorY)

        sliderX.onChange = { value, fromUser ->
            if (fromUser) {
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
                    resetAutoClose()
                }
            }
        )
        factorCard.addRow(sectionHint("0.50 – 2.50 · bước 0.05 · 1.00 = giữ nguyên"))
        box.addView(factorCard, topMargin())
        register(factorCard)

        buildEdgeCard(box)
    }

    private fun buildEdgeCard(box: LinearLayout) {
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
        val warning = sectionHint(
            "⚠ Chạm lại ở giữa: game có thể coi đó là một lần chạm MỚI (tối đa 1 lần/giây)."
        )
        val reanchorRow = ToggleRow(
            ctx, "Chạm lại ở giữa khi chạm mép", prefs.reanchor
        ).also { row ->
            register(row)
            row.onCheckedChange = { checked ->
                prefs.reanchor = checked
                warning.visibility = if (checked) View.VISIBLE else View.GONE
                cb.onSensitivitySettingsChanged()
                resetAutoClose()
            }
        }
        edgeCard.addRow(reanchorRow)
        warning.visibility = if (prefs.reanchor) View.VISIBLE else View.GONE
        edgeCard.addRow(warning)
        box.addView(edgeCard, topMargin())
        register(edgeCard)
    }

    private fun buildDragTab(box: LinearLayout) {
        box.addView(
            sectionHint(
                "Ngón chạm BÊN TRONG vòng tròn mới được biến đổi. Ngón đứng yên = không có " +
                    "sự kiện = không có đầu ra. Gain tối đa 4.00x."
            )
        )

        val zoneCard = SectionCard(ctx, "Vùng tròn")
        val sizeSlider = LabeledSlider(
            ctx, "Kích thước (dp)", 48f, 240f, 4f, prefs.dragSizeDp.toFloat()
        ).also { slider ->
            slider.onChange = { value, fromUser ->
                if (fromUser) {
                    prefs.dragSizeDp = value.roundToInt()
                    cb.onDragConfigChanged()
                    resetAutoClose()
                }
            }
            slider.onStopTracking = { cb.onDragZoneChanged(); resetAutoClose() }
            register(slider)
        }
        val alphaSlider = LabeledSlider(
            ctx, "Độ mờ", 0.05f, 0.8f, 0.05f, prefs.dragAlpha
        ).also { slider ->
            slider.onChange = { value, fromUser ->
                if (fromUser) {
                    prefs.dragAlpha = value
                    cb.onDragConfigChanged()
                    resetAutoClose()
                }
            }
            register(slider)
        }
        zoneCard.addRow(sizeSlider)
        zoneCard.addRow(alphaSlider)
        zoneCard.addRow(
            ToggleRow(ctx, "Khóa vị trí (chạm xuyên)", prefs.dragLocked).also { row ->
                register(row)
                row.onCheckedChange = { checked ->
                    prefs.dragLocked = checked
                    cb.onDragConfigChanged()
                    buildContent()
                    resetAutoClose()
                }
            }
        )
        zoneCard.addRow(
            sectionHint("Mở khóa = kéo thả vòng tròn, tự khóa lại sau 15 giây (đang tạm ngưng nhân).")
        )
        box.addView(zoneCard, topMargin())
        register(zoneCard)

        val effectCard = SectionCard(ctx, "Cộng thêm khi kéo")
        val valueSlider = LabeledSlider(
            ctx, "Giá trị cộng (%)", 0f, 100f, 1f, prefs.dragValue.toFloat()
        ).also { slider ->
            slider.onChange = { value, fromUser ->
                if (fromUser) {
                    prefs.dragValue = value.roundToInt()
                    cb.onDragConfigChanged()
                    resetAutoClose()
                }
            }
            register(slider)
        }
        effectCard.addRow(valueSlider)

        var shape = prefs.dragShape
        var easing = prefs.dragEasing
        val preview = CurvePreview(ctx).also { register(it) }
        preview.setDrag(shape, easing, prefs.dragNoiseAmp, prefs.dragNoisePeriod)

        val shapeRow = choiceRow(
            "Kiểu cộng",
            DRAG_SHAPE_NAMES,
            shape
        ) { index ->
            shape = index
            prefs.dragShape = index
            preview.setDrag(shape, easing, prefs.dragNoiseAmp, prefs.dragNoisePeriod)
            cb.onDragConfigChanged()
            buildContent()
            resetAutoClose()
        }
        effectCard.addRow(shapeRow)
        effectCard.addRow(sectionHint(DRAG_SHAPE_DESC[shape.coerceIn(0, DRAG_SHAPE_DESC.size - 1)]))

        effectCard.addRow(
            choiceRow("Dạng", EASING_NAMES, easing) { index ->
                easing = index
                prefs.dragEasing = index
                preview.setDrag(shape, easing, prefs.dragNoiseAmp, prefs.dragNoisePeriod)
                cb.onDragConfigChanged()
                buildContent()
                resetAutoClose()
            }
        )
        effectCard.addRow(
            choiceRow("Hướng áp dụng", DIRECTION_NAMES, prefs.dragDirection) { index ->
                prefs.dragDirection = index
                cb.onDragConfigChanged()
                buildContent()
                resetAutoClose()
            }
        )
        effectCard.addRow(
            LabeledSlider(
                ctx, "Quãng đạt tối đa (px)", 20f, 1000f, 10f, prefs.dragLengthRef.toFloat()
            ).also { slider ->
                slider.onChange = { value, fromUser ->
                    if (fromUser) {
                        prefs.dragLengthRef = value.roundToInt()
                        cb.onDragConfigChanged()
                        resetAutoClose()
                    }
                }
                register(slider)
            }
        )
        effectCard.addRow(
            ToggleRow(ctx, "Dừng khi ra khỏi vòng tròn", prefs.dragStopOutside).also { row ->
                register(row)
                row.onCheckedChange = { checked ->
                    prefs.dragStopOutside = checked
                    cb.onDragConfigChanged()
                    resetAutoClose()
                }
            }
        )
        effectCard.addRow(
            LabeledSlider(
                ctx, "Perlin: biên độ (%)", 0f, 50f, 1f, prefs.dragNoiseAmp.toFloat()
            ).also { slider ->
                slider.onChange = { value, fromUser ->
                    if (fromUser) {
                        prefs.dragNoiseAmp = value.roundToInt()
                        preview.setDrag(shape, easing, prefs.dragNoiseAmp, prefs.dragNoisePeriod)
                        cb.onDragConfigChanged()
                        resetAutoClose()
                    }
                }
                register(slider)
            }
        )
        effectCard.addRow(
            LabeledSlider(
                ctx, "Perlin: chu kỳ (px)", 20f, 300f, 10f, prefs.dragNoisePeriod.toFloat()
            ).also { slider ->
                slider.onChange = { value, fromUser ->
                    if (fromUser) {
                        prefs.dragNoisePeriod = value.roundToInt()
                        preview.setDrag(shape, easing, prefs.dragNoiseAmp, prefs.dragNoisePeriod)
                        cb.onDragConfigChanged()
                        resetAutoClose()
                    }
                }
                register(slider)
            }
        )
        effectCard.addRow(
            LabeledSlider(
                ctx, "Velocity: tốc độ tham chiếu (px/ms)", 0.1f, 10f, 0.1f,
                prefs.dragVelocityRef
            ).also { slider ->
                slider.onChange = { value, fromUser ->
                    if (fromUser) {
                        prefs.dragVelocityRef = value
                        cb.onDragConfigChanged()
                        resetAutoClose()
                    }
                }
                register(slider)
            }
        )
        effectCard.addRow(preview)
        effectCard.addRow(
            sectionHint(
                "Perlin dùng bảng hoán vị cố định: cùng một đường kéo luôn cho cùng kết quả, " +
                    "không rand/seed theo thời gian. Ngón đứng yên không sinh sự kiện."
            )
        )
        box.addView(effectCard, topMargin())
        register(effectCard)

        box.addView(
            Button(ctx).apply {
                text = "Đặt lại mặc định Drag"
                textSize = 13f
                setOnClickListener {
                    prefs.resetDragDefaults()
                    cb.onDragConfigChanged()
                    buildContent()
                    resetAutoClose()
                }
            },
            topMargin()
        )
        box.addView(edgeCardCopy())
    }

    private fun edgeCardCopy(): View {
        val holder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        buildEdgeCard(holder)
        return holder
    }

    private fun buildStyleTab(box: LinearLayout) {
        box.addView(
            sectionHint(
                "Style áp dụng TRƯỚC gain của mode. Tắt = Raw. Độ trễ thêm tối đa 8 ms ở " +
                    "mức mạnh nhất; giữ sub-pixel."
            )
        )
        val card = SectionCard(ctx, "Style chung")
        card.addRow(
            ToggleRow(ctx, "Bật Style", prefs.styleEnabled).also { row ->
                register(row)
                row.onCheckedChange = { checked ->
                    prefs.styleEnabled = checked
                    cb.onStyleChanged()
                    buildContent()
                    resetAutoClose()
                }
            }
        )
        card.addRow(
            choiceRow("Kiểu", STYLE_NAMES, prefs.styleId) { index ->
                prefs.styleId = index
                cb.onStyleChanged()
                buildContent()
                resetAutoClose()
            }
        )
        card.addRow(sectionHint(STYLE_DESC[prefs.styleId.coerceIn(0, STYLE_DESC.size - 1)]))

        val intensity = LabeledSlider(
            ctx, "Cường độ", 0f, 100f, 1f, prefs.styleIntensity.toFloat()
        ).also { slider ->
            slider.onChange = { value, fromUser ->
                if (fromUser) {
                    prefs.styleIntensity = value.roundToInt()
                    cb.onStyleChanged()
                    resetAutoClose()
                }
            }
            register(slider)
        }
        card.addRow(intensity)
        card.addRow(
            LabeledSlider(
                ctx, "Elastic: độ cứng", 40f, 900f, 20f, prefs.styleStiffness
            ).also { slider ->
                slider.onChange = { value, fromUser ->
                    if (fromUser) {
                        prefs.styleStiffness = value
                        cb.onStyleChanged()
                        resetAutoClose()
                    }
                }
                register(slider)
            }
        )
        card.addRow(
            LabeledSlider(
                ctx, "Elastic: giảm chấn", 2f, 120f, 2f, prefs.styleDamping
            ).also { slider ->
                slider.onChange = { value, fromUser ->
                    if (fromUser) {
                        prefs.styleDamping = value
                        cb.onStyleChanged()
                        resetAutoClose()
                    }
                }
                register(slider)
            }
        )
        val preview = CurvePreview(ctx).also { register(it) }
        preview.setStyle(prefs.styleId, prefs.styleIntensity)
        card.addRow(preview)
        card.addRow(
            Stepper(ctx, "Xem trước theo cường độ", "${prefs.styleIntensity}%") { delta ->
                prefs.styleIntensity =
                    (prefs.styleIntensity + delta * 5).coerceIn(0f, 100f).roundToInt()
                preview.setStyle(prefs.styleId, prefs.styleIntensity)
                intensity.setValue(prefs.styleIntensity.toFloat(), notify = false)
                cb.onStyleChanged()
                resetAutoClose()
            }.also { register(it) }
        )
        box.addView(card, topMargin())
        register(card)
    }

    private fun buildHudTab(box: LinearLayout) {
        val hudCard = SectionCard(ctx, "HUD")
        hudCard.addRow(
            ToggleRow(ctx, "Hiện HUD", prefs.hudEnabled).also { row ->
                register(row)
                row.onCheckedChange = { checked ->
                    prefs.hudEnabled = checked
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
            Stepper(ctx, "Cỡ chữ (cả 2 HUD)", "%.2f".format(prefs.hudTextScale)) { delta ->
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

    /** label + ‹ value › cycler, so no Dialog (and no window token) is needed. */
    private fun choiceRow(
        title: String,
        options: List<String>,
        selected: Int,
        onSelect: (Int) -> Unit
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(Kit.MIN_TOUCH_DP)
        }
        row.addView(
            TextView(ctx).apply {
                text = title
                setTextColor(Kit.textPrimary(ctx))
                textSize = 12f
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        val index = selected.coerceIn(0, options.size - 1)
        row.addView(IconButton(ctx, "‹") {
            onSelect((index - 1 + options.size) % options.size)
        })
        row.addView(
            TextView(ctx).apply {
                text = options[index]
                setTextColor(Kit.accent(ctx))
                textSize = 11f
                gravity = Gravity.CENTER
                minWidth = dp(92f)
                maxLines = 2
            }
        )
        row.addView(IconButton(ctx, "›") {
            onSelect((index + 1) % options.size)
        })
        return row
    }

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
        const val MODE_SENSITIVITY = 0
        const val MODE_DRAG = 1

        val DRAG_SHAPE_NAMES = listOf(
            "Linear", "Sine", "Quad", "Cubic", "Quart", "Quint", "Expo", "Circ",
            "Perlin Noise", "Velocity"
        )
        val DRAG_SHAPE_DESC = listOf(
            "Tuyến tính: gain tăng đều theo quãng kéo.",
            "Sine: khởi đầu êm, tăng nhanh ở giữa.",
            "Quad: bậc 2, tăng nhẹ rồi dốc dần.",
            "Cubic: bậc 3, càng kéo xa càng mạnh.",
            "Quart: bậc 4, rất nhẹ ở đầu.",
            "Quint: bậc 5, gần như phẳng lúc đầu.",
            "Expo: bùng nổ ở cuối quãng kéo.",
            "Circ: vòng tròn, tăng chậm và dốc mạnh.",
            "Perlin: cộng gợn cố định lên đường cong (cùng đường kéo → cùng kết quả).",
            "Velocity: gain theo tốc độ ngón (px/ms, đã làm mượt)."
        )
        val EASING_NAMES = listOf("In", "Out", "InOut")
        val DIRECTION_NAMES = listOf("Mọi hướng", "Ngang", "Dọc", "Lên", "Xuống")
        val STYLE_NAMES = listOf(
            "Raw", "Smooth", "Adaptive", "Snappy", "Elastic", "Precision",
            "S-curve", "Power", "Stepped"
        )
        val STYLE_DESC = listOf(
            "Raw: không xử lý, 1:1.",
            "Smooth: EMA trên delta, mượt nhưng trễ nhẹ.",
            "Adaptive: bộ lọc 1€, chậm thì lọc mạnh, nhanh gần như không lọc.",
            "Snappy: 0 ms trễ, 1:1 từng pixel, dừng ngay khi ngón dừng.",
            "Elastic: lò xo bậc hai, vọt nhẹ khi đang di chuyển, không trôi khi dừng.",
            "Precision: gain thấp khi chậm, 1.0 khi nhanh.",
            "S-curve: gain theo tốc độ dạng sigmoid.",
            "Power: gain = v^p, p theo cường độ.",
            "Stepped: lượng tử hóa gain theo bậc."
        )

        fun modeLabel(mode: Int): String = if (mode == MODE_DRAG) "Mode: Drag" else "Mode: Độ nhạy"
    }
}
