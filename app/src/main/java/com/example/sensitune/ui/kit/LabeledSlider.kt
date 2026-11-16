package com.example.sensitune.ui.kit

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * label + numeric value + SeekBar, mapping an int progress to a float value.
 *
 * progress = round((value - min) / step) and value = min + progress * step.
 * Plain view: usable in an Activity layout and in a WindowManager overlay.
 */
class LabeledSlider(
    context: Context,
    val label: String,
    private val minValue: Float,
    private val maxValue: Float,
    private val step: Float,
    initialValue: Float
) : LinearLayout(context), KitReleasable {

    /** Fired on every value change; fromUser is false for programmatic updates. */
    var onChange: ((Float, Boolean) -> Unit)? = null

    /** Fired when the finger leaves the bar (send the final value here). */
    var onStopTracking: ((Float) -> Unit)? = null

    private val steps: Int = if (step <= 0f) 0 else ((maxValue - minValue) / step).roundToInt()
    private val titleView: TextView
    private val valueView: TextView
    private val seekBar: SeekBar
    private var suppress = false

    init {
        orientation = VERTICAL
        setPadding(0, Kit.dp(context, 4f), 0, Kit.dp(context, 4f))
        gravity = Gravity.CENTER_VERTICAL
        minimumWidth = Kit.dp(context, 120f)

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleView = TextView(context).apply {
            text = label
            setTextColor(Kit.textPrimary(context))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
        }
        valueView = TextView(context).apply {
            setTextColor(Kit.accent(context))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.END
            minWidth = Kit.dp(context, 48f)
        }
        header.addView(
            titleView,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(valueView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        seekBar = SeekBar(context).apply {
            max = steps
            // Fixed >= 40dp height: a SeekBar draws a thin track, but its whole
            // view is the touch target and must not steal the parent's scroll
            // gesture with a tiny hit area.
            minimumHeight = Kit.dp(context, Kit.MIN_TOUCH_DP)
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                Kit.dp(context, Kit.MIN_TOUCH_DP + 4f)
            )
        }
        addView(seekBar, seekBar.layoutParams)

        setValue(initialValue, notify = false)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = valueFromProgress(progress)
                renderValue(value)
                if (!suppress) onChange?.invoke(value, fromUser)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}

            override fun onStopTrackingTouch(bar: SeekBar?) {
                if (!suppress) onStopTracking?.invoke(currentValue())
            }
        })
    }

    fun currentValue(): Float = valueFromProgress(seekBar.progress)

    fun setValue(value: Float, notify: Boolean = false) {
        val progress = progressFromValue(value)
        suppress = true
        seekBar.progress = progress
        suppress = false
        renderValue(valueFromProgress(progress))
        if (notify) onChange?.invoke(currentValue(), false)
    }

    fun setLabel(text: String) {
        titleView.text = text
    }

    /** Cancels every callback so a detached overlay cannot leak its host. */
    override fun release() {
        seekBar.setOnSeekBarChangeListener(null)
        onChange = null
        onStopTracking = null
        removeAllViews()
    }

    fun detach() = release()

    private fun progressFromValue(value: Float): Int {
        if (steps <= 0) return 0
        return ((value - minValue) / step).roundToInt().coerceIn(0, steps)
    }

    private fun valueFromProgress(progress: Int): Float {
        if (steps <= 0) return minValue
        val raw = minValue + progress.coerceIn(0, steps) * step
        return (raw * 1000f).roundToInt() / 1000f
    }

    private fun renderValue(value: Float) {
        valueView.text = String.format(java.util.Locale.US, "%.2f", value)
        valueView.contentDescription = "$label ${valueView.text}"
    }
}
