package com.teest.ambilight

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/** Colour calibration and temporal smoothing for all LEDs. Values are floats in 0..255. */
class ColorProcessor(private val n: Int) {

    /** Smoothed output colours (r,g,b per LED). */
    val cur = FloatArray(n * 3)
    private val cal = FloatArray(n * 3)

    fun process(target: FloatArray, cfg: Config, dtMs: Float, tauMs: Float, snap: Boolean) {
        calibrate(target, cfg)
        if (snap) {
            cal.copyInto(cur)
            return
        }
        val tau = if (tauMs < 0f) 0f else tauMs
        for (i in 0 until n) {
            val o = i * 3
            val diff = abs(cal[o] - cur[o]) + abs(cal[o + 1] - cur[o + 1]) + abs(cal[o + 2] - cur[o + 2])
            // Hard scene changes should not feel sluggish.
            val t = if (diff > 300f) tau * 0.4f else tau
            val alpha = if (t <= 1f) 1f else 1f - exp(-dtMs / t)
            cur[o] += (cal[o] - cur[o]) * alpha
            cur[o + 1] += (cal[o + 1] - cur[o + 1]) * alpha
            cur[o + 2] += (cal[o + 2] - cur[o + 2]) * alpha
        }
    }

    private fun calibrate(t: FloatArray, cfg: Config) {
        val sat = cfg.saturation / 100f
        val gamma = cfg.gamma
        val bri = cfg.brightness / 100f
        val gr = cfg.gainR / 100f * bri
        val gg = cfg.gainG / 100f * bri
        val gb = cfg.gainB / 100f * bri
        for (i in 0 until n) {
            val o = i * 3
            var r = t[o] / 255f
            var g = t[o + 1] / 255f
            var b = t[o + 2] / 255f
            if (sat != 1f) {
                val l = 0.299f * r + 0.587f * g + 0.114f * b
                r = l + (r - l) * sat
                g = l + (g - l) * sat
                b = l + (b - l) * sat
            }
            r = r.coerceIn(0f, 1f)
            g = g.coerceIn(0f, 1f)
            b = b.coerceIn(0f, 1f)
            if (gamma != 1f) {
                r = r.pow(gamma)
                g = g.pow(gamma)
                b = b.pow(gamma)
            }
            cal[o] = r * gr * 255f
            cal[o + 1] = g * gg * 255f
            cal[o + 2] = b * gb * 255f
        }
    }

    /** Writes the current colours as RGB bytes into out, starting at pixel index [startPixel]. */
    fun writeTo(out: ByteArray, startPixel: Int) = writeTo(cur, out, startPixel)

    /** Writes the given colours (r,g,b floats per LED) as RGB bytes into out. */
    fun writeTo(src: FloatArray, out: ByteArray, startPixel: Int) {
        for (i in 0 until n) {
            val d = (startPixel + i) * 3
            val o = i * 3
            if (d + 2 >= out.size) break
            out[d] = (src[o] + 0.5f).toInt().coerceIn(0, 255).toByte()
            out[d + 1] = (src[o + 1] + 0.5f).toInt().coerceIn(0, 255).toByte()
            out[d + 2] = (src[o + 2] + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
    }
}
