package com.teest.ambilight

import kotlin.math.max

/**
 * Maps every LED of the strip to a region at the edge of the picture.
 *
 * All coordinates are normalised (0..1) relative to the visible picture (after black bars were removed).
 * "dotX/dotY" is only used by the preview and lies just outside the picture.
 */
class LedLayout(val spec: LayoutSpec) {

    class Region(
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
        val dotX: Float,
        val dotY: Float,
    )

    /** Number of LEDs that actually belong to the picture edge. */
    val count: Int = spec.top + spec.right + spec.bottom + spec.left

    /** Number of pixels that are sent to WLED (including skipped and unused LEDs). */
    val totalLeds: Int = max(spec.totalLeds, spec.skipLeading + count)

    /** Region of LED k, where k is the index inside the used part of the strip. */
    val regions: Array<Region>

    init {
        val d = spec.depthPercent / 100f
        // Clockwise slots, starting at the top-left corner.
        val slots = ArrayList<Region>(count)
        for (i in 0 until spec.top) {
            val x0 = i.toFloat() / spec.top
            val x1 = (i + 1).toFloat() / spec.top
            slots.add(Region(x0, 0f, x1, d, (x0 + x1) / 2f, -0.06f))
        }
        for (i in 0 until spec.right) {
            val y0 = i.toFloat() / spec.right
            val y1 = (i + 1).toFloat() / spec.right
            slots.add(Region(1f - d, y0, 1f, y1, 1.06f, (y0 + y1) / 2f))
        }
        for (i in 0 until spec.bottom) {
            val x1 = 1f - i.toFloat() / spec.bottom
            val x0 = 1f - (i + 1).toFloat() / spec.bottom
            slots.add(Region(x0, 1f - d, x1, 1f, (x0 + x1) / 2f, 1.06f))
        }
        for (i in 0 until spec.left) {
            val y1 = 1f - i.toFloat() / spec.left
            val y0 = 1f - (i + 1).toFloat() / spec.left
            slots.add(Region(0f, y0, d, y1, -0.06f, (y0 + y1) / 2f))
        }

        regions = if (count == 0) {
            emptyArray<Region>()
        } else {
            // First slot (clockwise order) that belongs to the chosen start corner.
            var start = when (spec.corner) {
                StartCorner.TOP_LEFT -> 0
                StartCorner.TOP_RIGHT -> spec.top
                StartCorner.BOTTOM_RIGHT -> spec.top + spec.right
                StartCorner.BOTTOM_LEFT -> spec.top + spec.right + spec.bottom
            }
            // Counter-clockwise the first LED sits on the slot before the corner.
            if (!spec.clockwise) start -= 1
            val dir = if (spec.clockwise) 1 else -1
            Array(count) { k ->
                slots[Math.floorMod(start + dir * k + spec.offset, count)]
            }
        }
    }
}
