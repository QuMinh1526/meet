package com.example.sensitune.ui.kit

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Label + current value + −/+ buttons. Used for HUD text size, opacity, temp
 * thresholds and list reordering where a slider is overkill.
 */
class Stepper(
    context: Context,
    label: String,
    valueText: String,
    private val delta: Float = 0.1f,
    onDelta: ((Float) -> Unit)? = null
) : LinearLayout(context), KitReleasable {

    var onStep: ((Float) -> Unit)? = onDelta
    var onStepUp: (() -> Unit)? = null
    var onStepDown: (() -> Unit)? = null

    private val labelView: TextView
    private val valueView: TextView
    private val minus: IconButton
    private val plus: IconButton

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = Kit.dp(context, Kit.MIN_TOUCH_DP)

        labelView = TextView(context).apply {
            text = label
            setTextColor(Kit.textPrimary(context))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        valueView = TextView(context).apply {
            text = valueText
            setTextColor(Kit.textSecondary(context))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            minWidth = Kit.dp(context, 48f)
        }
        minus = IconButton(context, "−") { step(-1f) }
        plus = IconButton(context, "+") { step(1f) }

        addView(labelView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(minus, LayoutParams(LayoutParams.WRAP_CONTENT, Kit.dp(context, Kit.MIN_TOUCH_DP)))
        addView(valueView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(plus, LayoutParams(LayoutParams.WRAP_CONTENT, Kit.dp(context, Kit.MIN_TOUCH_DP)))
    }

    fun setValueText(text: String) {
        valueView.text = text
    }

    fun setLabel(text: String) {
        labelView.text = text
    }

    private fun step(direction: Float) {
        if (direction < 0) onStepDown?.invoke() else onStepUp?.invoke()
        onStep?.invoke(direction * delta)
    }

    override fun release() {
        minus.release()
        plus.release()
        onStep = null
        onStepUp = null
        onStepDown = null
        removeAllViews()
    }

    fun detach() = release()
}
