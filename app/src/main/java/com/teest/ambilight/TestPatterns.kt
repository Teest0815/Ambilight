package com.teest.ambilight

enum class TestKind { LAYOUT, DIM, JUMP, WHEEL, ALL }

/**
 * Timing and colours of the test pictures that are drawn on the TV screen (see TestView).
 * They are captured and processed by the normal Ambilight pipeline, so the whole chain is tested.
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

    private const val LAYOUT_LAP_MS = 6000L
    private const val LAYOUT_LAPS = 2

    /** Time with a plain grey picture before the pattern starts, so the capture can settle. */
    const val LEAD_IN_MS = 1500L

    private val SINGLE = listOf(TestKind.DIM, TestKind.JUMP, TestKind.WHEEL)

    /** Length of the pattern itself (without lead-in). */
    fun durationMs(kind: TestKind): Long = when (kind) {
        TestKind.LAYOUT -> LAYOUT_LAP_MS * LAYOUT_LAPS
        TestKind.DIM -> DIM_COLORS.size * DIM_STEP_MS
        TestKind.JUMP -> JUMP_COLORS.size * JUMP_STEP_MS * JUMP_ROUNDS
        TestKind.WHEEL -> WHEEL_TURN_MS * WHEEL_TURNS
        TestKind.ALL -> SINGLE.sumOf { durationMs(it) }
    }

    fun title(kind: TestKind): String = when (kind) {
        TestKind.LAYOUT -> "Layout"
        TestKind.DIM -> "Dimmen"
        TestKind.JUMP -> "Farbsprünge"
        TestKind.WHEEL -> "RGB-Rad"
        TestKind.ALL -> "Alles nacheinander"
    }

    /** Which single pattern runs at time tMs, and how far into it we are. */
    fun resolve(kind: TestKind, tMs: Long): Pair<TestKind, Long> {
        if (kind != TestKind.ALL) return kind to tMs
        var rest = tMs
        for (part in SINGLE) {
            val d = durationMs(part)
            if (rest < d) return part to rest
            rest -= d
        }
        return TestKind.WHEEL to durationMs(TestKind.WHEEL) - 1
    }

    /** Colour of the whole screen for DIM and JUMP, t = time inside that pattern. */
    fun fullScreenColor(part: TestKind, t: Long): Int = when (part) {
        TestKind.DIM -> {
            val idx = ((t / DIM_STEP_MS) % DIM_COLORS.size).toInt()
            val local = (t % DIM_STEP_MS).toFloat() / DIM_STEP_MS
            val tri = if (local < 0.5f) local * 2f else (1f - local) * 2f
            scale(DIM_COLORS[idx], tri * tri) // squared: looks more even to the eye
        }
        else -> JUMP_COLORS[((t / JUMP_STEP_MS) % JUMP_COLORS.size).toInt()]
    }

    /** Rotation of the colour wheel as a fraction of a turn (0..1). */
    fun wheelTurn(t: Long): Float = (t % WHEEL_TURN_MS).toFloat() / WHEEL_TURN_MS

    /** Position of the running marker on the screen border, 0..1 clockwise from the top-left corner. */
    fun layoutMarker(t: Long): Float = (t % LAYOUT_LAP_MS).toFloat() / LAYOUT_LAP_MS

    private fun scale(rgb: Int, f: Float): Int {
        val r = (((rgb shr 16) and 0xFF) * f + 0.5f).toInt()
        val g = (((rgb shr 8) and 0xFF) * f + 0.5f).toInt()
        val b = ((rgb and 0xFF) * f + 0.5f).toInt()
        return (r shl 16) or (g shl 8) or b
    }
}
