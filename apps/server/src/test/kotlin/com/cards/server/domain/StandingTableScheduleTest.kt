package com.dangerfield.cards.server.domain

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

internal fun sampleStandingTable(code: String = "K7M2QX", owner: UserId = UserId(UUID.randomUUID())) = StandingTable(
    code, owner, "Thursday Night Poker", 5000, 6, DayOfWeek.THURSDAY, LocalTime(20, 0),
    TimeZone.of("America/New_York"), createdAt = Instant.parse("2026-11-01T00:00:00Z"), updatedAt = Instant.parse("2026-11-01T00:00:00Z"),
)

class StandingTableScheduleTest {
    @Test fun beforeDuringAndAfterWindow() {
        val table = sampleStandingTable()
        for (now in listOf("2026-11-19T20:00:00Z", "2026-11-20T01:30:00Z", "2026-11-20T03:00:00Z")) {
            assertEquals(Instant.parse("2026-11-20T01:00:00Z"), nextStart(table, Instant.parse(now)))
        }
        assertEquals(Instant.parse("2026-11-27T01:00:00Z"), nextStart(table, Instant.parse("2026-11-20T03:00:01Z")))
    }
    @Test fun sundayWrapsToMonday() {
        val table = sampleStandingTable().copy(weekday = DayOfWeek.MONDAY)
        assertEquals(Instant.parse("2026-11-17T01:00:00Z"), nextStart(table, Instant.parse("2026-11-15T12:00:00Z")))
    }
    @Test fun springForwardAndFallBackUseLocalTime() {
        val table = sampleStandingTable().copy(weekday = DayOfWeek.SUNDAY, localTime = LocalTime(2, 30))
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), nextStart(table, Instant.parse("2026-03-07T12:00:00Z")))
        val fall = table.copy(localTime = LocalTime(1, 30))
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), nextStart(fall, Instant.parse("2026-10-31T12:00:00Z")))
    }
    @Test fun tokyoWeekdayCanDifferFromUtc() {
        val table = sampleStandingTable().copy(weekday = DayOfWeek.MONDAY, localTime = LocalTime(0, 15), timeZone = TimeZone.of("Asia/Tokyo"))
        assertEquals(Instant.parse("2026-11-15T15:15:00Z"), nextStart(table, Instant.parse("2026-11-15T13:00:00Z")))
    }
}
