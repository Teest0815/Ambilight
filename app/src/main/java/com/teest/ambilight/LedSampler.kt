package com.teest.ambilight

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Computes the average colour of every LED region.
 * The pixel bounds per LED only change with the layout or the picture crop, so they are cached
 * and not recalculated for every frame.
 */
class LedSampler {
    private var layout: LedLayout? = null
    private var kx0 = 0
    private var ky0 = 0
    private var kx1 = 0
    private var ky1 = 0
    private var valid = false

    private var px0 = IntArray(0)
    private var py0 = IntArray(0)
    private var px1 = IntArray(0)
    private var py1 = IntArray(0)

    /** Picture rectangle [cx0,cx1) x [cy0,cy1) inside the frame (frame minus black bars). */
    fun update(l: LedLayout, cx0: Int, cy0: Int, cx1: Int, cy1: Int) {
        if (valid && l === layout && cx0 == kx0 && cy0 == ky0 && cx1 == kx1 && cy1 == ky1) return
        layout = l
        kx0 = cx0
        ky0 = cy0
        kx1 = cx1
        ky1 = cy1
        valid = true

        val n = l.count
        if (px0.size != n) {
            px0 = IntArray(n)
            py0 = IntArray(n)
            px1 = IntArray(n)
            py1 = IntArray(n)
        }
        val cw = cx1 - cx0
        val ch = cy1 - cy0
        for (k in 0 until n) {
            val r = l.regions[k]
            var a = cx0 + (r.x0 * cw).toInt()
            var b = cx0 + ceil(r.x1 * cw).toInt()
            var c = cy0 + (r.y0 * ch).toInt()
            var d = cy0 + ceil(r.y1 * ch).toInt()
            a = a.coerceIn(cx0, cx1 - 1)
            c = c.coerceIn(cy0, cy1 - 1)
            b = max(a + 1, min(b, cx1))
            d = max(c + 1, min(d, cy1))
            px0[k] = a
            px1[k] = b
            py0[k] = c
            py1[k] = d
        }
    }

    /** out receives r,g,b (0..255) per LED. update() must have been called before. */
    fun colors(b: ByteArray, stride: Int, vibrant: Boolean, out: FloatArray) {
        val n = layout?.count ?: return
        for (k in 0 until n) {
            val x0 = px0[k]
            val x1 = px1[k]
            val y0 = py0[k]
            val y1 = py1[k]
            val o = k * 3
            if (!vibrant) {
                var sr = 0
                var sg = 0
                var sb = 0
                for (y in y0 until y1) {
                    var i = y * stride + x0 * 4
                    for (x in x0 until x1) {
                        sr += b[i].toInt() and 0xFF
                        sg += b[i + 1].toInt() and 0xFF
                        sb += b[i + 2].toInt() and 0xFF
                        i += 4
                    }
                }
                val cnt = ((x1 - x0) * (y1 - y0)).coerceAtLeast(1)
                out[o] = sr.toFloat() / cnt
                out[o + 1] = sg.toFloat() / cnt
                out[o + 2] = sb.toFloat() / cnt
            } else {
                var sr = 0f
                var sg = 0f
                var sb = 0f
                var sw = 0f
                for (y in y0 until y1) {
                    var i = y * stride + x0 * 4
                    for (x in x0 until x1) {
                        val rr = b[i].toInt() and 0xFF
                        val gg = b[i + 1].toInt() and 0xFF
                        val bb = b[i + 2].toInt() and 0xFF
                        val s = (max(rr, max(gg, bb)) - min(rr, min(gg, bb))) / 255f
                        val w = 0.1f + s * s * 4f
                        sr += rr * w
                        sg += gg * w
                        sb += bb * w
                        sw += w
                        i += 4
                    }
                }
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
}
