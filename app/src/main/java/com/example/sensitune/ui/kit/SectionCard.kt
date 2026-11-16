package com.example.sensitune.ui.kit

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Rounded grouping card with an optional title. Groups kit rows on the main
 * settings screen as well as inside the floating panel.
 */
class SectionCard(
    context: Context,
    title: String? = null
) : LinearLayout(context), KitReleasable {

    private val titleView: TextView?
    val content: LinearLayout

    init {
        orientation = VERTICAL
        setPadding(Kit.dp(context, 10f), Kit.dp(context, 8f), Kit.dp(context, 10f), Kit.dp(context, 8f))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = Kit.dp(context, 10f).toFloat()
            setColor(Kit.cardBackground(context))
            setStroke(Kit.dp(context, 1f).coerceAtLeast(1), Kit.cardStroke(context))
        }

        titleView = if (title.isNullOrBlank()) {
            null
        } else {
            TextView(context).apply {
                text = title
                setTextColor(Kit.textSecondary(context))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
                gravity = Gravity.START
                setPadding(0, 0, 0, Kit.dp(context, 4f))
            }.also {
                addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
        }

        content = LinearLayout(context).apply { orientation = VERTICAL }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setTitle(text: String) {
        titleView?.text = text
    }

    fun addRow(view: View, params: LayoutParams = LayoutParams(
        LayoutParams.MATCH_PARENT,
        LayoutParams.WRAP_CONTENT
    )) {
        content.addView(view, params)
    }

    fun addDivider() {
        addRow(View(context).apply {
            setBackgroundColor(Kit.cardStroke(context))
        }, LayoutParams(LayoutParams.MATCH_PARENT, Kit.dp(context, 1f).coerceAtLeast(1)).apply {
            topMargin = Kit.dp(context, 4f)
            bottomMargin = Kit.dp(context, 4f)
        })
    }

    override fun release() {
        removeAllViews()
    }

    fun detach() = release()
}
