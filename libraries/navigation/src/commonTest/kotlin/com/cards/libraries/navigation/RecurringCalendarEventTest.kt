package com.dangerfield.cards.libraries.navigation

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class RecurringCalendarEventTest {
    @Test
    fun descriptionDoesNotRepeatAUrlTheNotesAlreadyCarry() {
        val url = "https://downcard.app/t/K7M2QX"
        assertEquals("Tap to join: $url", event(notes = "Tap to join: $url", url = url).descriptionWithUrl)
        assertEquals("Bring snacks\n$url", event(notes = "Bring snacks", url = url).descriptionWithUrl)
    }

    @Test
    fun weeklyRuleLeavesTheWeekdayToTheStartDate() {
        assertEquals("FREQ=WEEKLY", WEEKLY_RECURRENCE_RULE)
    }

    private fun event(notes: String, url: String) = RecurringCalendarEvent(
        title = "Thursday Night Poker",
        notes = notes,
        url = url,
        firstStart = Instant.parse("2026-11-20T01:00:00Z"),
        durationMinutes = 120,
        timeZone = TimeZone.of("America/New_York"),
        weekday = DayOfWeek.THURSDAY,
    )
}
