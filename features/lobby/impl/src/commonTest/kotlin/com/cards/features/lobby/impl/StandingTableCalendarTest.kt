package com.dangerfield.cards.features.lobby.impl

import com.dangerfield.cards.features.lobby.nextValidCalendarStart
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class StandingTableCalendarTest {
    @Test
    fun springGapSkipsFirstOccurrenceSoWeeklyRuleKeepsNominalTime() {
        assertEquals(Instant.parse("2026-03-15T06:30:00Z"), nextValidCalendarStart(
            Instant.parse("2026-03-08T07:30:00Z"), LocalTime(2, 30), DayOfWeek.SUNDAY, TimeZone.of("America/New_York")))
    }

    @Test
    fun normalAndFallBackOccurrencesKeepTheirFirstStart() {
        listOf("2026-11-01T05:30:00Z", "2026-11-08T06:30:00Z").forEach { start ->
            val instant = Instant.parse(start)
            assertEquals(instant, nextValidCalendarStart(instant, LocalTime(1, 30), DayOfWeek.SUNDAY, TimeZone.of("America/New_York")))
        }
    }
}
