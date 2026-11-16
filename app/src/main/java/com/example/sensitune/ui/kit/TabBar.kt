package com.example.sensitune.ui.kit

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Horizontal tab strip. Tabs share the width equally, so it collapses correctly
 * on narrow screens (no fixed pixel widths).
 */
class TabBar(
    context: Context,
    tabs: List<String>,
    onSelect: ((Int) -> Unit)? = null
) : LinearLayout(context), KitReleasable {

    var onTabSelected: ((Int) -> Unit)? = onSelect

    private val tabViews = ArrayList<TextView>()
    private var selected = 0

    init {
        orientation = HORIZONTAL
        for ((index, title) in tabs.withIndex()) {
            val view = TextView(context).apply {
                text = title
                gravity = Gravity.CENTER
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                minimumHeight = Kit.dp(context, Kit.MIN_TOUCH_DP)
                setPadding(
                    Kit.dp(context, 8f),
                    Kit.dp(context, 8f),
                    Kit.dp(context, 8f),
                    Kit.dp(context, 8f)
                )
                setOnClickListener {
                    select(index, notify = true)
                }
            }
            tabViews.add(view)
            addView(view, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        render()
    }

    fun selectedIndex(): Int = selected

    fun select(index: Int, notify: Boolean = false) {
        if (tabViews.isEmpty()) return
        selected = index.coerceIn(0, tabViews.size - 1)
        render()
        if (notify) onTabSelected?.invoke(selected)
    }

    private fun render() {
        for ((index, view) in tabViews.withIndex()) {
            val active = index == selected
            view.setTextColor(if (active) Color.WHITE else Kit.textSecondary(context))
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = Kit.dp(context, 8f).toFloat()
                setColor(
                    if (active) Kit.accent(context)
                    else if (Kit.isDark(context)) 0x22000000 else 0x0D000000
                )
            }
            view.isSelected = active
        }
    }

    override fun release() {
        for (view in tabViews) view.setOnClickListener(null)
        tabViews.clear()
        removeAllViews()
        onTabSelected = null
    }

    fun detach() = release()
}
