package com.example.sensitune.ui.kit

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Compact square-ish button for symbolic actions (↑ ↓ ← → ✕ …). Always at least
 * 40dp so it is a valid touch target.
 */
class IconButton(
    context: Context,
    icon: String,
    onClick: (() -> Unit)? = null
) : FrameLayout(context), KitReleasable {

    var onIconClick: (() -> Unit)? = onClick

    private val labelView: TextView

    init {
        minimumWidth = Kit.dp(context, Kit.MIN_TOUCH_DP)
        minimumHeight = Kit.dp(context, Kit.MIN_TOUCH_DP)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = Kit.dp(context, 8f).toFloat()
            setColor(if (Kit.isDark(context)) 0x22FFFFFF else 0x11000000)
            setStroke(Kit.dp(context, 1f).coerceAtLeast(1), Kit.cardStroke(context))
        }
        labelView = TextView(context).apply {
            text = icon
            gravity = Gravity.CENTER
            setTextColor(Kit.textPrimary(context))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            contentDescription = icon
        }
        addView(labelView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setOnClickListener { onIconClick?.invoke() }
        isClickable = true
        isFocusable = true
    }

    fun setIcon(icon: String) {
        labelView.text = icon
        labelView.contentDescription = icon
    }

    override fun release() {
        setOnClickListener(null)
        onIconClick = null
    }

    fun detach() = release()
}
