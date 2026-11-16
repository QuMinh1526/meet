package com.example.sensitune

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.sensitune.ui.kit.CurvePreview
import com.example.sensitune.ui.kit.IconButton
import com.example.sensitune.ui.kit.LabeledSlider
import com.example.sensitune.ui.kit.SectionCard
import com.example.sensitune.ui.kit.ToggleRow
import kotlin.math.roundToInt

/**
 * Full DRAG + STYLE settings with the shared ui.kit widgets. Every change is
 * pushed to the engine through the shell UserService (Binder on a worker thread)
 * and the DragZoneView is refreshed through SensiService.
 */
class DragSettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var column: LinearLayout
    private lateinit var preview: CurvePreview
    private var ioThread: HandlerThread? = null
    private var io: Handler? = null

    private val pushRunnable = Runnable {
        try {
            ShizukuHelper.setMode(prefs.mode)
            ShizukuHelper.setStyle(
                prefs.styleId, prefs.styleEnabled, prefs.styleIntensity,
                prefs.styleStiffness, prefs.styleDamping
            )
            ShizukuHelper.setDragConfig(
                prefs.dragValue, prefs.dragShape, prefs.dragEasing,
                prefs.dragDirection, prefs.dragLengthRef, prefs.dragStopOutside,
                prefs.dragNoiseAmp, prefs.dragNoisePeriod, prefs.dragVelocityRef
            )
        } catch (t: Throwable) {
            LogBuffer.error("DragSettings", "push failed", t)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setTitle(R.string.drag_settings_title)
        val scroll = ScrollView(this)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(10f), dp(14f), dp(24f))
        }
        scroll.addView(column)
        setContentView(scroll)
        build()
    }

    private fun build() {
        column.removeAllViews()

        val modeCard = SectionCard(this, getString(R.string.mode_section))
        modeCard.addRow(
            Button(this).apply {
                text = if (prefs.mode == 1) getString(R.string.mode_drag)
                else getString(R.string.mode_sensitivity)
                textSize = 14f
                minimumHeight = dp(48f)
                setOnClickListener {
                    prefs.mode = if (prefs.mode == 1) 0 else 1
                    pushAll()
                    build()
                }
            }
        )
        column.addView(modeCard, lp(top = 0f))

        val styleCard = SectionCard(this, "Style chung (áp dụng trước gain)")
        styleCard.addRow(
            ToggleRow(this, "Bật Style", prefs.styleEnabled).also { row ->
                row.onCheckedChange = {
                    prefs.styleEnabled = it
                    pushAll()
                    build()
                }
            }
        )
        styleCard.addRow(choiceRow("Kiểu", STYLE_NAMES, prefs.styleId) { index ->
            prefs.styleId = index
            pushAll()
            build()
        })
        styleCard.addRow(hint(STYLE_DESC[prefs.styleId.coerceIn(0, STYLE_DESC.size - 1)]))
        styleCard.addRow(LabeledSlider(
            this, "Cường độ", 0f, 100f, 1f, prefs.styleIntensity.toFloat()
        ).also { slider ->
            slider.onChange = { value, fromUser ->
                if (fromUser) {
                    prefs.styleIntensity = value.roundToInt()
                    preview.setStyle(prefs.styleId, prefs.styleIntensity)
                    pushAll()
                }
            }
            slider.onStopTracking = { updatePreview() }
        })
        styleCard.addRow(LabeledSlider(
            this, "Elastic: độ cứng", 40f, 900f, 20f, prefs.styleStiffness
        ).also { slider ->
            slider.onChange = { value, fromUser ->
                if (fromUser) {
                    prefs.styleStiffness = value
                    pushAll()
                }
            }
        })
        styleCard.addRow(LabeledSlider(
            this, "Elastic: giảm chấn", 2f, 120f, 2f, prefs.styleDamping
        ).also { slider ->
            slider.onChange = { value, fromUser ->
                if (fromUser) {
                    prefs.styleDamping = value
                    pushAll()
                }
            }
        })
        preview = CurvePreview(this)
        styleCard.addRow(preview)
        column.addView(styleCard, lp())

        val circle = SectionCard(this, "Vùng tròn Drag")
        circle.addRow(LabeledSlider(
            this, "Kích thước (dp)", 48f, 240f, 4f, prefs.dragSizeDp.toFloat()
        ).also { slider ->
            slider.onChange = { value, fromUser ->
                if (fromUser) {
                    prefs.dragSizeDp = value.roundToInt()
                    pushAll()
                }
            }
            slider.onStopTracking = { notifyService() }
        })
        circle.addRow(LabeledSlider(
            this, "Độ mờ", 0.05f, 0.8f, 0.05f, prefs.dragAlpha
        ).also { slider ->
            slider.onChange = { value, fromUser ->
                if (fromUser) {
                    prefs.dragAlpha = value
                    notifyService()
                }
            }
        })
        circle.addRow(
            ToggleRow(this, "Khóa vị trí (chạm xuyên)", prefs.dragLocked).also { row ->
                row.onCheckedChange = {
                    prefs.dragLocked = it
                    notifyService()
                }
            }
        )
        column.addView(circle, lp())

        val drag = SectionCard(this, "Cộng thêm khi kéo")
        drag.addRow(LabeledSlider(
            this, "Giá trị cộng (%)", 0f, 100f, 1f, prefs.dragValue.toFloat()
        ).also { slider ->
            slider.onChange = { value, fromUser ->
                if (fromUser) {
                    prefs.dragValue = value.roundToInt()
                    pushAll()
                }
            }
        })
        drag.addRow(choiceRow("Kiểu cộng", DRAG_SHAPE_NAMES, prefs.dragShape) { index ->
            prefs.dragShape = index
            pushAll()
            build()
        })
        drag.addRow(hint(DRAG_SHAPE_DESC[prefs.dragShape.coerceIn(0, DRAG_SHAPE_DESC.size - 1)]))
        drag.addRow(choiceRow("Dạng", EASING, prefs.dragEasing) { index ->
            prefs.dragEasing = index
            pushAll()
            build()
        })
        drag.addRow(choiceRow("Hướng áp dụng", DIRECTIONS, prefs.dragDirection) { index ->
            prefs.dragDirection = index
            pushAll()
        })
        drag.addRow(LabeledSlider(
            this, "Quãng đạt tối đa (px)", 20f, 1000f, 10f, prefs.dragLengthRef.toFloat()
        ).also { slider ->
            slider.onChange = { value, fromUser ->
                if (fromUser) {
                    prefs.dragLengthRef = value.roundToInt()
                    pushAll()
                }
            }
        })
        drag.addRow(
            ToggleRow(this, "Dừng khi ra khỏi vòng tròn", prefs.dragStopOutside).also { row ->
                row.onCheckedChange = {
                    prefs.dragStopOutside = it
                    pushAll()
                }
            }
        )
        drag.addRow(LabeledSlider(
            this, "Perlin: biên độ (%)", 0f, 50f, 1f, prefs.dragNoiseAmp.toFloat()
        ).also { slider ->
            slider.onChange = { value, fromUser ->
                if (fromUser) {
                    prefs.dragNoiseAmp = value.roundToInt()
                    updatePreview()
                    pushAll()
                }
            }
        })
        drag.addRow(LabeledSlider(
            this, "Perlin: chu kỳ (px)", 20f, 300f, 10f, prefs.dragNoisePeriod.toFloat()
        ).also { slider ->
            slider.onChange = { value, fromUser ->
                if (fromUser) {
                    prefs.dragNoisePeriod = value.roundToInt()
                    updatePreview()
                    pushAll()
                }
            }
        })
        drag.addRow(LabeledSlider(
            this, "Velocity: tốc độ tham chiếu (px/ms)", 0.1f, 10f, 0.1f,
            prefs.dragVelocityRef
        ).also { slider ->
            slider.onChange = { value, fromUser ->
                if (fromUser) {
                    prefs.dragVelocityRef = value
                    pushAll()
                }
            }
        })
        drag.addRow(hint(
            "Perlin dùng bảng hoán vị cố định, không rand/seed. Cùng đường kéo → cùng kết quả. " +
                "Gain tối đa 4.00x. Ngón đứng yên không sinh đầu ra."
        ))
        column.addView(drag, lp())

        column.addView(Button(this).apply {
            text = getString(R.string.open_testpad)
            setOnClickListener { startActivity(Intent(this@DragSettingsActivity, TestPadActivity::class.java)) }
        }, lp())

        column.addView(Button(this).apply {
            text = getString(R.string.reset_drag)
            setOnClickListener {
                prefs.resetDragDefaults()
                pushAll()
                notifyService()
                build()
                Toast.makeText(this@DragSettingsActivity, R.string.reset_done, Toast.LENGTH_SHORT).show()
            }
        }, lp())

        updatePreview()
    }

    private fun updatePreview() {
        if (prefs.styleEnabled || prefs.mode == 0) {
            preview.setStyle(prefs.styleId, prefs.styleIntensity)
        } else {
            preview.setDrag(
                prefs.dragShape, prefs.dragEasing, prefs.dragNoiseAmp, prefs.dragNoisePeriod
            )
        }
    }

    private fun pushAll() {
        val h = ioHandler()
        h.removeCallbacks(pushRunnable)
        h.postDelayed(pushRunnable, 50L)
        notifyService()
    }

    /** The circle itself lives in SensiService; ask it to re-read Prefs + re-send the zone. */
    private fun notifyService() {
        try {
            startService(Intent(this, SensiService::class.java).setAction(Actions.REFRESH_CONFIG))
        } catch (t: Throwable) {
            LogBuffer.error("DragSettings", "notifyService failed", t)
        }
    }

    private fun ioHandler(): Handler {
        val existing = io
        if (existing != null && ioThread?.isAlive == true) return existing
        val t = HandlerThread("drag-settings-io").also { it.start() }
        ioThread = t
        val h = Handler(t.looper)
        io = h
        return h
    }

    private fun choiceRow(
        title: String,
        options: List<String>,
        selected: Int,
        onSelect: (Int) -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(40f)
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 12f
            setTextColor(Color.DKGRAY)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val index = selected.coerceIn(0, options.size - 1)
        row.addView(IconButton(this, "‹") { onSelect((index - 1 + options.size) % options.size) })
        row.addView(TextView(this).apply {
            text = options[index]
            textSize = 11f
            minWidth = dp(92f)
            gravity = android.view.Gravity.CENTER
        })
        row.addView(IconButton(this, "›") { onSelect((index + 1) % options.size) })
        return row
    }

    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 9f
        setTextColor(Color.GRAY)
        setPadding(0, dp(2f), 0, dp(6f))
    }

    private fun lp(top: Float = 8f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(top) }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()

    override fun onDestroy() {
        ioThread?.quitSafely()
        ioThread = null
        io = null
        super.onDestroy()
    }

    companion object {
        val DRAG_SHAPE_NAMES = FloatMenu.DRAG_SHAPE_NAMES
        val DRAG_SHAPE_DESC = FloatMenu.DRAG_SHAPE_DESC
        val EASING = FloatMenu.EASING_NAMES
        val DIRECTIONS = FloatMenu.DIRECTION_NAMES
        val STYLE_NAMES = FloatMenu.STYLE_NAMES
        val STYLE_DESC = FloatMenu.STYLE_DESC
    }
}
