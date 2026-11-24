package com.teest.ambilight

/**
 * Blends from a frozen snapshot of what is currently shown to a live source over a fixed time.
 * Used for every mode change (ambient light, black screen, standby), so nothing jumps.
 */
class Crossfade(ledCount: Int) {
    private val from = FloatArray(ledCount * 3)
    private var startMs = 0L
    private var durMs = 1L

    var active = false
        private set

    /** Starts a fade from [shown] (what the strip shows now). durationMs <= 0 means no fade. */
    fun begin(shown: FloatArray, now: Long, durationMs: Int) {
        if (durationMs <= 0 || shown.size != from.size) {
            active = false
            return
        }
        shown.copyInto(from)
        startMs = now
        durMs = durationMs.toLong()
        active = true
    }

    /** Writes the blended colours into dst (r,g,b per LED, 0..255). */
    fun apply(src: FloatArray, now: Long, dst: FloatArray) {
        if (!active) {
            src.copyInto(dst)
            return
        }
        val t = (now - startMs).toFloat() / durMs
        if (t >= 1f) {
            active = false
            src.copyInto(dst)
            return
        }
        val e = t * t * (3f - 2f * t) // smoothstep
        for (i in dst.indices) dst[i] = from[i] + (src[i] - from[i]) * e
    }
}
