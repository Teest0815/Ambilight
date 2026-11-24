package com.teest.ambilight

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
