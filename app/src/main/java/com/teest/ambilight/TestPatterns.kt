package com.teest.ambilight

import kotlin.math.abs

enum class TestKind { DIM, JUMP, WHEEL, ALL }

/**
 * Test patterns for the LED strip, independent of the screen:
 * dimming (white and the three base colours fade up and down), colours that change abruptly, and an RGB wheel.
 * Colours are packed as 0xRRGGBB.
 */
object TestPatterns {
    private const val DIM_STEP_MS = 2500L
    private val DIM_COLORS = intArrayOf(0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF)

    private const val JUMP_STEP_MS = 600L
    private const val JUMP_ROUNDS = 2
    private val JUMP_COLORS = intArrayOf(0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF, 0xFFFFFF)

    private const val WHEEL_TURN_MS = 4000L
    private const val WHEEL_TURNS = 2

    private val SINGLE = listOf(TestKind.DIM, TestKind.JUMP, TestKind.WHEEL)

    fun durationMs(kind: TestKind): Long = when (kind) {
        TestKind.DIM -> DIM_COLORS.size * DIM_STEP_MS
        TestKind.JUMP -> JUMP_COLORS.size * JUMP_STEP_MS * JUMP_ROUNDS
        TestKind.WHEEL -> WHEEL_TURN_MS * WHEEL_TURNS
        TestKind.ALL -> SINGLE.sumOf { durationMs(it) }
    }

    /** Name of the part that runs at time tMs (for the status line). */
    fun label(kind: TestKind, tMs: Long): String {
        val (part, _) = resolve(kind, tMs)
        return when (part) {
            TestKind.DIM -> "Dimmen"
            TestKind.JUMP -> "Farbsprünge"
            else -> "RGB-Rad"
        }
    }

    /** Fills out[0 until n] with the colour of every LED at time tMs. */
    fun render(kind: TestKind, tMs: Long, n: Int, out: IntArray) {
        val (part, t) = resolve(kind, tMs)
        when (part) {
            TestKind.DIM -> {
                val idx = ((t / DIM_STEP_MS) % DIM_COLORS.size).toInt()
                val local = (t % DIM_STEP_MS).toFloat() / DIM_STEP_MS
                val tri = if (local < 0.5f) local * 2f else (1f - local) * 2f
                val level = tri * tri // squared: looks more even to the eye
                val c = scale(DIM_COLORS[idx], level)
                for (i in 0 until n) out[i] = c
            }
            TestKind.JUMP -> {
                val idx = ((t / JUMP_STEP_MS) % JUMP_COLORS.size).toInt()
                val c = JUMP_COLORS[idx]
                for (i in 0 until n) out[i] = c
            }
            else -> {
                val shift = (t % WHEEL_TURN_MS).toFloat() / WHEEL_TURN_MS
                for (i in 0 until n) {
                    val hue = (i.toFloat() / n + shift) % 1f
                    out[i] = hsvToRgb(hue)
                }
            }
        }
    }

    /** Which single pattern runs at tMs, and how far into it we are. */
    private fun resolve(kind: TestKind, tMs: Long): Pair<TestKind, Long> {
        if (kind != TestKind.ALL) return kind to tMs
        var rest = tMs
        for (part in SINGLE) {
            val d = durationMs(part)
            if (rest < d) return part to rest
            rest -= d
        }
        return TestKind.WHEEL to durationMs(TestKind.WHEEL) - 1
    }

    private fun scale(rgb: Int, f: Float): Int {
        val r = (((rgb shr 16) and 0xFF) * f + 0.5f).toInt()
        val g = (((rgb shr 8) and 0xFF) * f + 0.5f).toInt()
        val b = ((rgb and 0xFF) * f + 0.5f).toInt()
        return (r shl 16) or (g shl 8) or b
    }

    /** Hue 0..1 at full saturation and brightness. */
    private fun hsvToRgb(h: Float): Int {
        val x = h * 6f
        val r = (abs(x - 3f) - 1f).coerceIn(0f, 1f)
        val g = (2f - abs(x - 2f)).coerceIn(0f, 1f)
        val b = (2f - abs(x - 4f)).coerceIn(0f, 1f)
        return ((r * 255f + 0.5f).toInt() shl 16) or ((g * 255f + 0.5f).toInt() shl 8) or (b * 255f + 0.5f).toInt()
    }
}
