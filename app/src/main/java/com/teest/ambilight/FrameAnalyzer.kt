package com.teest.ambilight

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Pixel maths on the small RGBA frame (byte order R, G, B, A). */
object FrameAnalyzer {

    private fun luma(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) * 77 + (b[i + 1].toInt() and 0xFF) * 150 + (b[i + 2].toInt() and 0xFF) * 29) shr 8

    /** Average brightness (0..255) of the whole frame, sampled sparsely. */
    fun meanLuma(b: ByteArray, w: Int, h: Int, stride: Int): Float {
        var sum = 0L
        var n = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                sum += luma(b, y * stride + x * 4)
                n++
                x += 3
            }
            y += 3
        }
        return if (n == 0) 0f else sum.toFloat() / n
    }

    private fun rowBlack(b: ByteArray, stride: Int, y: Int, x0: Int, x1: Int, thr: Int): Boolean {
        val samples = (x1 - x0 + 1) / 2
        val allowed = samples / 50 // 2 % of the pixels may be bright (logos, subtitles)
        var bright = 0
        var x = x0
        while (x < x1) {
            if (luma(b, y * stride + x * 4) > thr) {
                bright++
                if (bright > allowed) return false
            }
            x += 2
        }
        return true
    }

    private fun colBlack(b: ByteArray, stride: Int, x: Int, y0: Int, y1: Int, thr: Int): Boolean {
        val samples = (y1 - y0 + 1) / 2
        val allowed = samples / 50
        var bright = 0
        var y = y0
        while (y < y1) {
            if (luma(b, y * stride + x * 4) > thr) {
                bright++
                if (bright > allowed) return false
            }
            y += 2
        }
        return true
    }

    /**
     * Measures black bars. out = [top, bottom, left, right] in pixels.
     * maxFrac limits how much of each side may be treated as a bar.
     */
    fun detectBars(b: ByteArray, w: Int, h: Int, stride: Int, thr: Int, maxFrac: Float, out: IntArray) {
        val maxRows = (h * maxFrac).toInt()
        val maxCols = (w * maxFrac).toInt()
        var top = 0
        while (top < maxRows && rowBlack(b, stride, top, 0, w, thr)) top++
        var bottom = 0
        while (bottom < maxRows && rowBlack(b, stride, h - 1 - bottom, 0, w, thr)) bottom++
        val y0 = top
        val y1 = h - bottom
        var left = 0
        while (left < maxCols && colBlack(b, stride, left, y0, y1, thr)) left++
        var right = 0
        while (right < maxCols && colBlack(b, stride, w - 1 - right, y0, y1, thr)) right++
        out[0] = top
        out[1] = bottom
        out[2] = left
        out[3] = right
    }

    /**
     * Average colour of every LED region inside the picture rectangle [cx0,cx1) x [cy0,cy1).
     * out receives r,g,b (0..255) per LED.
     */
    fun ledColors(
        b: ByteArray,
        stride: Int,
        cx0: Int,
        cy0: Int,
        cx1: Int,
        cy1: Int,
        layout: LedLayout,
        vibrant: Boolean,
        out: FloatArray,
    ) {
        val cw = cx1 - cx0
        val ch = cy1 - cy0
        for (k in 0 until layout.count) {
            val r = layout.regions[k]
            var px0 = cx0 + (r.x0 * cw).toInt()
            var px1 = cx0 + ceil(r.x1 * cw).toInt()
            var py0 = cy0 + (r.y0 * ch).toInt()
            var py1 = cy0 + ceil(r.y1 * ch).toInt()
            px0 = px0.coerceIn(cx0, cx1 - 1)
            py0 = py0.coerceIn(cy0, cy1 - 1)
            px1 = max(px0 + 1, min(px1, cx1))
            py1 = max(py0 + 1, min(py1, cy1))

            var sr = 0f
            var sg = 0f
            var sb = 0f
            var sw = 0f
            for (y in py0 until py1) {
                var i = y * stride + px0 * 4
                for (x in px0 until px1) {
                    val rr = b[i].toInt() and 0xFF
                    val gg = b[i + 1].toInt() and 0xFF
                    val bb = b[i + 2].toInt() and 0xFF
                    val wgt = if (vibrant) {
                        val s = (max(rr, max(gg, bb)) - min(rr, min(gg, bb))) / 255f
                        0.1f + s * s * 4f
                    } else {
                        1f
                    }
                    sr += rr * wgt
                    sg += gg * wgt
                    sb += bb * wgt
                    sw += wgt
                    i += 4
                }
            }
            val o = k * 3
            if (sw > 0f) {
                out[o] = sr / sw
                out[o + 1] = sg / sw
                out[o + 2] = sb / sw
            } else {
                out[o] = 0f
                out[o + 1] = 0f
                out[o + 2] = 0f
            }
        }
    }
}

/**
 * Turns the raw per-frame bar measurement into a stable crop.
 * Bars that disappear are applied at once (picture content would otherwise be cut off),
 * new or larger bars only after they stayed the same for the configured delay.
 */
class BarTracker {
    /** [top, bottom, left, right] in pixels. */
    val applied = IntArray(4)
    private val cand = IntArray(4)
    private var candSince = 0L
    private val m = IntArray(4)

    fun reset() {
        applied.fill(0)
        cand.fill(0)
        candSince = 0L
    }

    fun update(measured: IntArray, now: Long, delayMs: Long, symmetric: Boolean) {
        measured.copyInto(m)
        if (symmetric) {
            val v = min(m[0], m[1])
            m[0] = v
            m[1] = v
            val s = min(m[2], m[3])
            m[2] = s
            m[3] = s
        }
        var changed = false
        for (i in 0 until 4) {
            if (kotlin.math.abs(m[i] - cand[i]) > 1) changed = true
        }
        if (changed) {
            m.copyInto(cand)
            candSince = now
        }
        // Content grew into the cropped area: show it immediately.
        for (i in 0 until 4) {
            if (m[i] < applied[i]) applied[i] = m[i]
        }
        if (!changed && now - candSince >= delayMs) {
            cand.copyInto(applied)
        }
    }
}
