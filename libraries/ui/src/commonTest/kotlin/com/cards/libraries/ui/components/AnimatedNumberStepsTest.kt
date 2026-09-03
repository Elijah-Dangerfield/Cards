package com.dangerfield.cards.libraries.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnimatedNumberStepsTest {

    @Test
    fun landsExactlyOnTheTarget() {
        // The one thing that must never be approximate: a counter settling on a
        // rounded number ships a wrong balance to every user.
        assertEquals(12_500, quantizeRollingNumber(current = 12_500, start = 10_000, end = 12_500))
    }

    @Test
    fun boundsTheNumberOfDistinctStringsRendered() {
        // The whole point: one blob per step, not one per frame.
        val start = 10_000L
        val end = 12_500L
        val perFrame = (0..47).map { frame ->
            start + (end - start) * frame / 47
        }
        val rendered = perFrame.map { quantizeRollingNumber(it, start, end) }.distinct()
        assertTrue(
            rendered.size <= ROLLING_NUMBER_STEPS + 1,
            "48 frames should collapse to at most ${ROLLING_NUMBER_STEPS + 1} strings, got ${rendered.size}",
        )
    }

    @Test
    fun stillMovesEnoughToReadAsCounting() {
        val rendered = (0..47).map { frame ->
            quantizeRollingNumber(10_000L + 2_500L * frame / 47, 10_000, 12_500)
        }.distinct()
        assertTrue(rendered.size >= 8, "a roll that shows ${rendered.size} values reads as a jump, not a count")
    }

    @Test
    fun countsDownAsWellAsUp() {
        // The pot ship drains to zero, so the descending direction matters as
        // much as the ascending one.
        assertEquals(0, quantizeRollingNumber(current = 0, start = 5_000, end = 0))
        val mid = quantizeRollingNumber(current = 2_500, start = 5_000, end = 0)
        assertTrue(mid in 1..5_000, "midpoint $mid should sit inside the range")
    }

    @Test
    fun zeroLengthRollDoesNotDivideByZero() {
        assertEquals(500, quantizeRollingNumber(current = 500, start = 500, end = 500))
    }

    @Test
    fun tinyRollsStillStepByAtLeastOne() {
        // span 3 with 12 steps would give a step size of 0 — must clamp to 1.
        val rendered = (0..3).map { quantizeRollingNumber(it.toLong(), 0, 3) }
        assertEquals(listOf(0L, 1L, 2L, 3L), rendered)
    }

    @Test
    fun neverOvershootsPastTheTarget() {
        for (frame in 0..47) {
            val raw = 10_000 + 2_500L * frame / 47
            val q = quantizeRollingNumber(raw, 10_000, 12_500)
            assertTrue(q in 10_000..12_500, "frame $frame produced $q, outside the roll")
        }
    }
}
