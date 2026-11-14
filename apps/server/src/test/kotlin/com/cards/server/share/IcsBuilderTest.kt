package com.dangerfield.cards.server.share

import com.dangerfield.cards.server.domain.sampleStandingTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class IcsBuilderTest {
    private val now = Instant.parse("2026-11-14T12:00:00Z")

    @Test fun weeklyRulesUseEveryIsoWeekday() {
        assertEquals(listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU"), kotlinx.datetime.DayOfWeek.entries.map(::weeklyDay))
    }
    @Test fun weeklyEventMatchesGoldenFile() {
        val actual = IcsBuilder.build(sampleStandingTable(), "https://join.downcard.app/K7M2QX", now)
        val expected = checkNotNull(javaClass.getResource("/share/standing-table.ics")).readText().replace("\r\n", "\n").replace("\n", "\r\n")
        assertEquals(expected, actual)
    }
    @Test fun escapesValuesAndFoldsByUtf8OctetsWithoutBreakingCharacters() {
        val table = sampleStandingTable().copy(name = "Poker, friends; 😀\\".repeat(8))
        val actual = IcsBuilder.build(table, "https://join.downcard.app/K7M2QX", now)
        assertTrue(actual.split("\r\n").all { it.toByteArray(Charsets.UTF_8).size <= 75 })
        val unfolded = actual.replace("\r\n ", "")
        assertTrue(unfolded.contains("SUMMARY:Poker\\, friends\\; 😀\\\\"))
        assertTrue(unfolded.contains("DESCRIPTION:Tap to join: https://join.downcard.app/K7M2QX\\nRoom code K7M2QX"))
        assertEquals(actual, actual.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
    }
    @Test fun recurrenceKeepsNominalWallTimeWhenFirstStartFallsInSpringGap() {
        val table = sampleStandingTable().copy(weekday = kotlinx.datetime.DayOfWeek.SUNDAY, localTime = kotlinx.datetime.LocalTime(2, 30))
        val beforeGap = Instant.parse("2026-03-07T12:00:00Z")
        val ics = IcsBuilder.build(table, "https://join.downcard.app/K7M2QX", beforeGap)
        assertTrue(ics.contains("DTSTART;TZID=America/New_York:20260308T023000"))
        val google = java.net.URLDecoder.decode(googleCalendarUrl(table, "https://join.downcard.app/K7M2QX", beforeGap), Charsets.UTF_8)
        assertTrue(google.contains("dates=20260308T023000/20260308T043000"))
        assertTrue(scheduleText(table, beforeGap).contains("2:30 AM"))
        val nextWeek = IcsBuilder.build(table, "https://join.downcard.app/K7M2QX", Instant.parse("2026-03-14T12:00:00Z"))
        assertTrue(nextWeek.contains("DTSTART;TZID=America/New_York:20260315T023000"))
    }
    @Test fun recurrenceKeepsNominalWallTimeAcrossFallBack() {
        val table = sampleStandingTable().copy(weekday = kotlinx.datetime.DayOfWeek.SUNDAY, localTime = kotlinx.datetime.LocalTime(1, 30))
        for ((now, date) in listOf("2026-10-31T12:00:00Z" to "20261101", "2026-11-07T12:00:00Z" to "20261108")) {
            assertTrue(IcsBuilder.build(table, "https://join.downcard.app/K7M2QX", Instant.parse(now))
                .contains("DTSTART;TZID=America/New_York:${date}T013000"))
            val google = java.net.URLDecoder.decode(googleCalendarUrl(table, "https://join.downcard.app/K7M2QX", Instant.parse(now)), Charsets.UTF_8)
            assertTrue(google.contains("dates=${date}T013000/${date}T033000"))
        }
    }
    @Test fun googleCalendarLinkKeepsZoneRepeatAndInvite() {
        val url = java.net.URLDecoder.decode(googleCalendarUrl(sampleStandingTable(), "https://join.downcard.app/K7M2QX", now), Charsets.UTF_8)
        assertTrue(url.contains("recur=RRULE:FREQ=WEEKLY;BYDAY=TH"))
        assertTrue(url.contains("ctz=America/New_York"))
        assertTrue(url.contains("dates=20261119T200000/20261119T220000"))
        assertTrue(url.contains("Room code K7M2QX"))
    }
}
