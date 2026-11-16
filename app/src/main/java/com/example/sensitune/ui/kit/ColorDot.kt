package com.example.sensitune.ui.kit

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View

/**
 * Small status dot (send status, engine state…). 8dp visual, 16dp touch box.
 */
class ColorDot(
    context: Context,
    status: Int = Kit.neutral()
) : View(context), KitReleasable {

    private val dot = GradientDrawable()

    init {
        dot.shape = GradientDrawable.OVAL
        background = dot
        minimumWidth = Kit.dp(context, 16f)
        minimumHeight = Kit.dp(context, 16f)
        setStatus(status)
    }

    fun setStatus(color: Int, description: String? = null) {
        dot.setColor(color)
        contentDescription = description
        invalidate()
    }

    fun setOk(description: String? = null) = setStatus(Kit.ok(), description)

    fun setError(description: String? = null) = setStatus(Kit.danger(), description)

    fun clear(description: String? = null) = setStatus(Color.TRANSPARENT, description)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = Kit.dp(context, 16f)
        setMeasuredDimension(
            resolveSize(size, widthMeasureSpec),
            resolveSize(size, heightMeasureSpec)
        )
    }

    override fun release() {
        contentDescription = null
    }

    fun detach() = release()
}
