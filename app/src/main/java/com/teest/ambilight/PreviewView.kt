package com.teest.ambilight

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Draws the TV and the LEDs around it, in the live colours while the ambilight is running. */
class PreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var layoutModel: LedLayout? = null
    private var colors: IntArray? = null

    private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#26262C") }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 3f
    }
    private val rect = RectF()

    fun setLayoutModel(model: LedLayout) {
        layoutModel = model
        invalidate()
    }

    fun setColors(c: IntArray?) {
        colors = c
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val model = layoutModel ?: return
        val margin = min(width, height) * 0.09f
        val availW = width - 2 * margin
        val availH = height - 2 * margin
        if (availW <= 0 || availH <= 0) return

        // Keep a 16:9 screen.
        var tvW = availW
        var tvH = tvW * 9f / 16f
        if (tvH > availH) {
            tvH = availH
            tvW = tvH * 16f / 9f
        }
        val left = (width - tvW) / 2f
        val top = (height - tvH) / 2f
        rect.set(left, top, left + tvW, top + tvH)
        canvas.drawRoundRect(rect, 8f, 8f, screenPaint)

        val spec = model.spec
        val densest = max(max(spec.top, spec.bottom), 1)
        val radius = (tvW / densest * 0.35f).coerceIn(3f, 14f)
        val live = colors
        for (k in model.regions.indices) {
            val r = model.regions[k]
            val cx = left + r.dotX * tvW
            val cy = top + r.dotY * tvH
            dotPaint.color = live?.getOrNull(k) ?: Color.parseColor("#55555C")
            canvas.drawCircle(cx, cy, radius, dotPaint)
            if (k == 0) canvas.drawCircle(cx, cy, radius + 4f, ringPaint)
        }
    }
}
