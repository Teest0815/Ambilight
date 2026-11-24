package com.teest.ambilight

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SweepGradient
import android.view.View
import kotlin.math.min

/**
 * Full-screen test picture. The Ambilight captures it like any other picture,
 * so capture, colour calculation, smoothing, black-screen handling and sending are all tested.
 */
class TestView(context: Context) : View(context) {

    var kind: TestKind = TestKind.ALL

    /** Time since the test started, in ms. Set by the activity before every frame. */
    var tMs: Long = 0L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }
    private val wheelColors = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
    private var wheel: SweepGradient? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        wheel = SweepGradient(w / 2f, h / 2f, wheelColors, null)
        textPaint.textSize = min(w, h) * 0.04f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (tMs < TestPatterns.LEAD_IN_MS) {
            canvas.drawColor(Color.rgb(0x30, 0x30, 0x30))
            drawLabel(canvas, "Test startet …")
            return
        }
        val (part, t) = TestPatterns.resolve(kind, tMs - TestPatterns.LEAD_IN_MS)
        when (part) {
            TestKind.DIM, TestKind.JUMP -> canvas.drawColor(Color.BLACK or TestPatterns.fullScreenColor(part, t))
            TestKind.WHEEL -> drawWheel(canvas, w, h, TestPatterns.wheelTurn(t))
            else -> drawLayout(canvas, w, h, TestPatterns.layoutMarker(t))
        }
        drawLabel(canvas, "Test: " + TestPatterns.title(part) + "   ·   beliebige Taste beendet")
    }

    private fun drawLabel(canvas: Canvas, text: String) {
        canvas.drawText(text, width / 2f, height / 2f, textPaint)
    }

    /** Colour wheel over the whole screen, so every edge shows another colour that moves around. */
    private fun drawWheel(canvas: Canvas, w: Float, h: Float, turn: Float) {
        canvas.drawColor(Color.BLACK)
        canvas.save()
        canvas.rotate(turn * 360f, w / 2f, h / 2f)
        paint.shader = wheel
        canvas.drawRect(-w, -h, 2f * w, 2f * h, paint)
        paint.shader = null
        canvas.restore()
    }

    /**
     * Coloured edges (top red, right green, bottom blue, left yellow) and a white marker that runs clockwise
     * from the top-left corner. The marker position on the screen does not depend on the layout settings:
     * if the strip lights up at the same place, start corner, direction and LED counts are correct.
     */
    private fun drawLayout(canvas: Canvas, w: Float, h: Float, marker: Float) {
        canvas.drawColor(Color.rgb(0x20, 0x20, 0x20))
        val bandH = h * 0.14f
        val bandW = w * 0.14f
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(200, 170, 0) // left
        canvas.drawRect(0f, 0f, bandW, h, paint)
        paint.color = Color.rgb(0, 170, 0) // right
        canvas.drawRect(w - bandW, 0f, w, h, paint)
        paint.color = Color.rgb(200, 0, 0) // top
        canvas.drawRect(0f, 0f, w, bandH, paint)
        paint.color = Color.rgb(0, 0, 220) // bottom
        canvas.drawRect(0f, h - bandH, w, h, paint)

        // path along the middle of the bands
        val ix = bandW / 2f
        val iy = bandH / 2f
        val lh = w - 2f * ix
        val lv = h - 2f * iy
        val total = 2f * (lh + lv)
        val steps = 14
        val tail = 0.07f // length of the marker as a fraction of the border
        for (i in 0..steps) {
            val u = ((marker - tail * (steps - i) / steps) % 1f + 1f) % 1f
            var d = u * total
            val x: Float
            val y: Float
            var horizontal = true
            if (d < lh) {
                x = ix + d; y = iy
            } else {
                d -= lh
                if (d < lv) {
                    x = w - ix; y = iy + d; horizontal = false
                } else {
                    d -= lv
                    if (d < lh) {
                        x = w - ix - d; y = h - iy
                    } else {
                        d -= lh
                        x = ix; y = h - iy - d; horizontal = false
                    }
                }
            }
            val level = 90 + (165 * i) / steps // tail dim, head bright
            paint.color = Color.rgb(level, level, level)
            if (horizontal) {
                canvas.drawRect(x - w * 0.03f, y - h * 0.065f, x + w * 0.03f, y + h * 0.065f, paint)
            } else {
                canvas.drawRect(x - w * 0.065f, y - h * 0.03f, x + w * 0.065f, y + h * 0.03f, paint)
            }
        }
    }
}
