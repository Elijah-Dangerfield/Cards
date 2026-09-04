package com.dangerfield.cards.libraries.ui.components

import kotlin.math.abs
import kotlin.math.max

/**
 * How many distinct values a rolling number is allowed to show while it
 * animates.
 *
 * A rolling counter that reads its animation every frame renders a **different
 * string every frame**, and a different string is a different text layout and a
 * fresh Skia glyph blob. At 60fps that churns the GPU glyph cache, which is what
 * wedged the RenderThread in four production ANRs (ENG-49,
 * `docs/plans/renderthread-text-stall.md`).
 *
 * Public because two surfaces roll numbers: the chip odometer in this module and the
 * pot-ship pill in the room feature. Any new rolling counter should use it too.
 *
 * Twelve steps over a ~700ms roll is a value change roughly every 58ms. Fast
 * enough to still read as counting, ~4x fewer blobs than one per frame, and
 * nobody can read individual digits changing faster than this anyway.
 */
const val ROLLING_NUMBER_STEPS = 12

/**
 * Snaps [current] to one of at most [steps] values between [start] and [end].
 *
 * Anchored on [end] so the roll always finishes on the exact target — a counter
 * that settles on a rounded number is a bug you would ship to every user, which
 * is why the arithmetic works backwards from the destination rather than
 * forwards from the origin.
 *
 * Degrades to returning [end] when there is nothing to animate, so a zero-length
 * roll can't divide by zero.
 */
fun quantizeRollingNumber(
    current: Long,
    start: Long,
    end: Long,
    steps: Int = ROLLING_NUMBER_STEPS,
): Long {
    val span = abs(end - start)
    if (span == 0L || steps <= 0) return end
    val stepSize = max(1L, span / steps)
    val offsetFromEnd = current - end
    return end + (offsetFromEnd / stepSize) * stepSize
}
