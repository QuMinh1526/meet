package com.example.sensitune

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Test page: green = real finger path, red = output after STYLE + mode gain.
 * Uses the same native DSP as the engine (libsensi_engine loaded in-process),
 * so trying a style or a drag curve shows exactly what the engine computes.
 */
class TestPadActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var pad: PadView
    private lateinit var info: TextView
    private var nativeOk = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        nativeOk = try {
            System.loadLibrary("sensi_engine")
            NativeBridge.previewReset()
            true
        } catch (t: Throwable) {
            LogBuffer.error("TestPad", "native preview unavailable", t)
            false
        }

        setTitle(R.string.testpad_title)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controls.addView(Button(this).apply {
            text = "Style ‹"
            textSize = 11f
            setOnClickListener {
                prefs.styleId = (prefs.styleId - 1 + 9) % 9
                updateInfo()
            }
        })
        controls.addView(Button(this).apply {
            text = "›"
            textSize = 11f
            setOnClickListener {
                prefs.styleId = (prefs.styleId + 1) % 9
                updateInfo()
            }
        })
        controls.addView(Button(this).apply {
            text = "Kiểu ‹"
            textSize = 11f
            setOnClickListener {
                prefs.dragShape = (prefs.dragShape - 1 + 10) % 10
                updateInfo()
            }
        })
        controls.addView(Button(this).apply {
            text = "›"
            textSize = 11f
            setOnClickListener {
                prefs.dragShape = (prefs.dragShape + 1) % 10
                updateInfo()
            }
        })
        controls.addView(Button(this).apply {
            text = "Xóa"
            textSize = 11f
            setOnClickListener { pad.clear() }
        })
        root.addView(controls)
        info = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.DKGRAY)
        }
        root.addView(info)
        pad = PadView()
        root.addView(
            pad,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(6f) }
        )
        setContentView(root)
        updateInfo()
    }

    private fun updateInfo() {
        val mode = if (prefs.mode == 1) "DRAG" else "SENSITIVITY"
        info.text = getString(
            R.string.testpad_info,
            mode,
            FloatMenu.STYLE_NAMES[prefs.styleId],
            FloatMenu.DRAG_SHAPE_NAMES[prefs.dragShape],
            if (nativeOk) "native" else "fallback",
            "x=%.0f%%".format(prefs.dragValue.toFloat())
        )
    }

    private inner class PadView : View(this@TestPadActivity) {
        private val fingerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2E7D32")
            style = Paint.Style.STROKE
            strokeWidth = dp(3f).toFloat()
        }
        private val outPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D32F2F")
            style = Paint.Style.STROKE
            strokeWidth = dp(2f).toFloat()
        }
        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#661565C0")
            style = Paint.Style.STROKE
            strokeWidth = dp(2f).toFloat()
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = dp(12f).toFloat()
        }

        private val finger = Path()
        private val output = Path()
        private var outX = 0f
        private var outY = 0f
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var pathLen = 0f
        private var speed = 0f
        private var gain = 1f
        private var inside = false
        private var started = false

        fun clear() {
            finger.reset()
            output.reset()
            pathLen = 0f
            gain = 1f
            started = false
            NativeBridge.previewReset()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(if (isDark) Color.parseColor("#101418") else Color.WHITE)
            if (prefs.mode == 1) {
                val r = dp(prefs.dragSizeDp.toFloat() / 2f).toFloat()
                canvas.drawCircle(width / 2f, height / 2f, r, circlePaint)
            }
            canvas.drawPath(finger, fingerPaint)
            canvas.drawPath(output, outPaint)
            canvas.drawText(
                "gain %.2fx · L %.0fpx · %s".format(
                    gain, pathLen, if (inside) "trong vòng" else "ngoài vòng"
                ),
                dp(8f).toFloat(), dp(20f).toFloat(), textPaint
            )
            canvas.drawText("xanh = ngón · đỏ = đầu ra", dp(8f).toFloat(),
                dp(36f).toFloat(), textPaint)
        }

        private val isDark: Boolean
            get() = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    finger.moveTo(event.x, event.y)
                    output.moveTo(event.x, event.y)
                    outX = event.x
                    outY = event.y
                    downX = event.x
                    downY = event.y
                    lastX = event.x
                    lastY = event.y
                    pathLen = 0f
                    speed = 0f
                    gain = 1f
                    started = true
                    val r = dp(prefs.dragSizeDp.toFloat() / 2f)
                    inside = prefs.mode == 0 ||
                        hypot(event.x - width / 2f, event.y - height / 2f) <= r
                    if (nativeOk) NativeBridge.previewReset()
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!started) return true
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (dx == 0f && dy == 0f) return true
                    val dtMs = (event.eventTime - event.downTime).coerceAtLeast(1L)
                        .toFloat()
                    val stepMs = 8f.coerceAtMost(dtMs)
                    var ox = dx
                    var oy = dy
                    if (prefs.styleEnabled && nativeOk) {
                        val styled = NativeBridge.previewStyle(
                            prefs.styleId, prefs.styleIntensity,
                            prefs.styleStiffness, prefs.styleDamping,
                            dx, dy, stepMs
                        )
                        if (styled != null && styled.size == 2) {
                            ox = styled[0]
                            oy = styled[1]
                        }
                    }
                    if (prefs.mode == 1) {
                        val r = dp(prefs.dragSizeDp.toFloat() / 2f)
                        if (prefs.dragStopOutside &&
                            hypot(event.x - width / 2f, event.y - height / 2f) > r
                        ) {
                            inside = false
                        }
                        if (inside) {
                            pathLen += hypot(dx, dy)
                            val inst = hypot(dx, dy) / stepMs
                            speed = if (speed <= 0f) inst else speed * 0.7f + inst * 0.3f
                            val u = (pathLen / prefs.dragLengthRef.coerceAtLeast(1))
                                .coerceIn(0f, 1f)
                            val extra = if (nativeOk) {
                                NativeBridge.dragPreviewExtra(
                                    prefs.dragShape, prefs.dragEasing,
                                    prefs.dragNoiseAmp / 100f,
                                    prefs.dragNoisePeriod.toFloat(), u
                                )
                            } else u
                            gain = (1f + prefs.dragValue / 100f * 3f * extra)
                                .coerceIn(1f, 4f)
                            if (!directionAllows(dx, dy)) gain = 1f
                            ox *= gain
                            oy *= gain
                        } else {
                            gain = 1f
                        }
                    } else {
                        // Screen X gets factorX, screen Y gets factorY (engine maps
                        // this to raw axes according to the display rotation).
                        gain = maxOf(prefs.factorX, prefs.factorY)
                        ox *= prefs.factorX
                        oy *= prefs.factorY
                    }
                    finger.lineTo(event.x, event.y)
                    outX += ox
                    outY += oy
                    output.lineTo(outX, outY)
                    lastX = event.x
                    lastY = event.y
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    started = false
                    invalidate()
                }
            }
            return true
        }

        private fun directionAllows(dx: Float, dy: Float): Boolean {
            val ax = abs(dx)
            val ay = abs(dy)
            return when (prefs.dragDirection) {
                1 -> ax >= ay
                2 -> ay > ax
                3 -> dy < 0 && ay >= ax
                4 -> dy > 0 && ay >= ax
                else -> true
            }
        }
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()

    override fun onDestroy() {
        super.onDestroy()
    }
}
