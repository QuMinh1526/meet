package com.example.sensitune.ui.kit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.example.sensitune.NativeBridge
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Small preview graph (plain View). Draws the selected drag curve or the gain of
 * the selected STYLE so a setting is never just a label.
 */
class CurvePreview(context: Context) : View(context), KitReleasable {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var dragShape = 0
    private var dragEasing = 0
    private var noiseAmp = 0.15f
    private var noisePeriod = 90

    private var styleId = -1
    private var styleIntensity = 50

    init {
        minimumHeight = Kit.dp(context, 110f)
    }

    /** Shows extra(u) of the drag curve. Pass shape >= 0 to switch to drag mode. */
    fun setDrag(shape: Int, easing: Int, ampPct: Int, periodPx: Int) {
        dragShape = shape
        dragEasing = easing
        noiseAmp = ampPct / 100f
        noisePeriod = periodPx
        styleId = -1
        invalidate()
    }

    /** Shows the gain curve of a STYLE (id 0..8). */
    fun setStyle(id: Int, intensity: Int) {
        styleId = id
        styleIntensity = intensity
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = Kit.dp(context, 10f).toFloat()
        val w = width - pad * 2
        val h = height - pad * 2
        if (w <= 0 || h <= 0) return

        paint.style = Paint.Style.FILL
        paint.color = if (Kit.isDark(context)) 0x22000000 else 0x0A000000
        canvas.drawRect(pad, pad, pad + w, pad + h, paint)

        paint.strokeWidth = Kit.dp(context, 1f).toFloat()
        paint.color = Kit.cardStroke(context)
        for (i in 0..4) {
            val y = pad + h * i / 4f
            canvas.drawLine(pad, y, pad + w, y, paint)
        }

        paint.strokeWidth = Kit.dp(context, 2f).toFloat()
        paint.color = Kit.accent(context)
        paint.style = Paint.Style.STROKE
        val path = Path()
        val n = 96
        for (i in 0..n) {
            val u = i / n.toFloat()
            val v = if (styleId >= 0) styleGain(styleId, styleIntensity / 100f, u)
            else if (NativeBridge.ensurePreviewLoaded()) {
                NativeBridge.dragPreviewExtra(
                    dragShape, dragEasing, noiseAmp, noisePeriod.toFloat(), u
                )
            } else {
                fallbackDrag(u)
            }
            val x = pad + w * u
            val y = pad + h * (1f - v.coerceIn(0f, 1f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)

        paint.style = Paint.Style.FILL
        paint.textSize = Kit.sp(context, 9f)
        paint.color = Kit.textSecondary(context)
        canvas.drawText("0", pad, height - 1f, paint)
        val label = if (styleId >= 0) "Gain theo tốc độ" else "Extra theo quãng kéo"
        canvas.drawText(label, pad, pad + paint.textSize, paint)
    }

    /** Only used if the native library cannot be loaded (never expected). */
    private fun fallbackDrag(u: Float): Float {
        val x = u.coerceIn(0f, 1f)
        return when (dragShape) {
            1 -> 1f - kotlin.math.cos(Math.PI.toFloat() * x / 2f)
            2 -> x * x
            3 -> x * x * x
            4 -> x * x * x * x
            5 -> x * x * x * x * x
            6 -> (2f.pow(10f * x) - 1f) / 1023f
            7 -> 1f - kotlin.math.sqrt((1f - x * x).coerceAtLeast(0f))
            else -> x
        }
    }

    private fun styleGain(id: Int, i: Float, u: Float): Float {
        val speed = u * 1.2f
        val s = (speed / 1.2f).coerceIn(0f, 1f)
        return when (id) {
            4 -> 1f
            5 -> 1f - i * 0.65f * (1f - s)
            6 -> 1f - i * 0.6f * (1f - (1f / (1f + kotlin.math.exp(-6f * (s - 0.45f)))))
            7 -> s.coerceAtLeast(0.0001f).pow(0.5f + 1.5f * i)
                .coerceIn(0.35f, 1f)
            8 -> {
                val steps = (1f + 5f * i).roundToInt().coerceAtLeast(1)
                val q = kotlin.math.round(s * steps) / steps
                0.4f + 0.6f * q
            }
            else -> u
        }
    }

    override fun release() {
        // no listeners
    }
}
