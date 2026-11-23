package com.teest.ambilight

import android.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random

/** Built-in effects that are shown while the screen is black. Output is r,g,b (0..255) per LED. */
class EffectEngine {
    private var sparkle = FloatArray(0)
    private val rnd = Random.Default
    private val hsv = FloatArray(3)

    fun render(
        name: String,
        tMs: Long,
        dtMs: Float,
        n: Int,
        color: Int,
        speed: Int,
        brightness: Float,
        out: FloatArray,
    ) {
        if (n == 0) return
        val cr = Color.red(color) * brightness
        val cg = Color.green(color) * brightness
        val cb = Color.blue(color) * brightness
        val sec = tMs / 1000f
        val dps = 10f + speed * 3.5f // hue degrees per second

        when (name) {
            "rainbow" -> {
                for (i in 0 until n) {
                    val hue = ((i.toFloat() / n) * 360f + sec * dps) % 360f
                    setHsv(out, i, hue, brightness)
                }
            }
            "color_cycle" -> {
                val hue = (sec * dps) % 360f
                for (i in 0 until n) setHsv(out, i, hue, brightness)
            }
            "comet" -> {
                val lps = n * (0.1f + speed / 100f * 0.6f)
                val pos = (sec * lps) % n
                val tail = maxOf(3f, n / 5f)
                for (i in 0 until n) {
                    val d = (((pos - i) % n) + n) % n
                    val level = if (d > tail) 0f else exp(-3f * d / tail)
                    set(out, i, cr * level, cg * level, cb * level)
                }
            }
            "sparkle" -> {
                if (sparkle.size != n) sparkle = FloatArray(n)
                val decay = 0.5f.pow(dtMs / 150f)
                val p = (0.2f + speed / 100f * 1.5f) * dtMs / 1000f
                for (i in 0 until n) {
                    sparkle[i] *= decay
                    if (rnd.nextFloat() < p) sparkle[i] = 1f
                    val level = sparkle[i]
                    set(out, i, cr * level, cg * level, cb * level)
                }
            }
            else -> { // breathing
                val hz = 0.05f + speed / 100f * 0.45f
                val phase = sec * hz
                val level = 0.08f + 0.92f * ((1f - cos(2.0 * PI * phase).toFloat()) / 2f)
                for (i in 0 until n) set(out, i, cr * level, cg * level, cb * level)
            }
        }
    }

    private fun set(out: FloatArray, i: Int, r: Float, g: Float, b: Float) {
        val o = i * 3
        out[o] = r
        out[o + 1] = g
        out[o + 2] = b
    }

    private fun setHsv(out: FloatArray, i: Int, hue: Float, value: Float) {
        hsv[0] = hue
        hsv[1] = 1f
        hsv[2] = value.coerceIn(0f, 1f)
        val c = Color.HSVToColor(hsv)
        set(out, i, Color.red(c).toFloat(), Color.green(c).toFloat(), Color.blue(c).toFloat())
    }
}
