package com.dangerfield.cards.features.shop.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Pins [formatCountdown]'s unit-dropping ladder — the badge shows the two
 * most significant units and falls off the right side as the window
 * shrinks.
 */
class CountdownFormatTest {

    @Test
    fun zeroAndNegativeRenderAsZeroSeconds() {
        assertEquals("0s", formatCountdown(0))
        assertEquals("0s", formatCountdown(-5_000))
    }

    @Test
    fun underAMinuteShowsSecondsOnly() {
        assertEquals("45s", formatCountdown(45.seconds.inWholeMilliseconds))
    }

    @Test
    fun underAnHourShowsMinutesAndSeconds() {
        assertEquals("12m 34s", formatCountdown((12.minutes + 34.seconds).inWholeMilliseconds))
    }

    @Test
    fun underADayShowsHoursAndMinutes() {
        assertEquals("4h 12m", formatCountdown((4.hours + 12.minutes + 59.seconds).inWholeMilliseconds))
    }

    @Test
    fun overADayShowsDaysAndHours() {
        assertEquals("3d 4h", formatCountdown((3.days + 4.hours + 30.minutes).inWholeMilliseconds))
    }

    @Test
    fun exactBoundariesDropTheSmallerUnit() {
        assertEquals("1m 0s", formatCountdown(1.minutes.inWholeMilliseconds))
        assertEquals("1h 0m", formatCountdown(1.hours.inWholeMilliseconds))
        assertEquals("1d 0h", formatCountdown(1.days.inWholeMilliseconds))
    }
}
