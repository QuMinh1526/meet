package com.example.sensitune.ui.kit

import android.content.Context
import android.view.Gravity
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * One setting row: label on the left, Switch on the right. The whole row is
 * clickable and at least 40dp tall. Plain view, no AppCompatSwitch.
 */
class ToggleRow(
    context: Context,
    label: String,
    checked: Boolean
) : LinearLayout(context), KitReleasable {

    var onCheckedChange: ((Boolean) -> Unit)? = null

    private val labelView: TextView
    private val switchView: Switch
    private var suppress = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = Kit.dp(context, Kit.MIN_TOUCH_DP)
        setPadding(0, Kit.dp(context, 2f), 0, Kit.dp(context, 2f))

        labelView = TextView(context).apply {
            text = label
            setTextColor(Kit.textPrimary(context))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 2
        }
        switchView = Switch(context).apply {
            setTextColor(Kit.textPrimary(context))
            isChecked = checked
            contentDescription = label
            setOnCheckedChangeListener { _: CompoundButton, value: Boolean ->
                if (!suppress) onCheckedChange?.invoke(value)
            }
        }
        addView(labelView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(switchView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        setOnClickListener {
            if (!switchView.isEnabled) return@setOnClickListener
            switchView.isChecked = !switchView.isChecked
        }
    }

    fun isChecked(): Boolean = switchView.isChecked

    fun setChecked(checked: Boolean, notify: Boolean = false) {
        suppress = true
        switchView.isChecked = checked
        suppress = false
        if (notify) onCheckedChange?.invoke(checked)
    }

    fun setLabel(text: String) {
        labelView.text = text
        switchView.contentDescription = text
    }

    fun setRowEnabled(enabled: Boolean) {
        switchView.isEnabled = enabled
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.5f
    }

    override fun release() {
        switchView.setOnCheckedChangeListener(null)
        setOnClickListener(null)
        onCheckedChange = null
        removeAllViews()
    }

    fun detach() = release()
}
