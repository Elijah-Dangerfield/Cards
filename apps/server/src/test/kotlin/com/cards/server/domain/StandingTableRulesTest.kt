package com.dangerfield.cards.server.domain

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StandingTableRulesTest {
    private fun invalid(name: String = "Poker", buyIn: Long = 5000, seats: Int = 6, weekday: Int = 4,
                        time: String = "20:00", zone: String = "America/New_York", duration: Int = 120) =
        StandingTableRules.invalidField(name, buyIn, seats, weekday, time, zone, duration)
    @Test fun normalizesWhitespace() { assertEquals("Thursday Poker", StandingTableRules.normalizedName(" Thursday   Poker ")); assertNull(invalid()) }
    @Test fun rejectsNames() { for (name in listOf(" ", "x".repeat(33), "hi\nthere", "a\u0000b")) assertEquals("name", invalid(name = name)) }
    @Test fun rejectsInvisibleFormatCharacters() {
        for (name in listOf("\u202Erekop", "Po\u200Bker", "\u2066Poker\u2069", "\uFEFFPoker", "Pok\u200Der", "Poker\u200E")) {
            assertEquals("name", invalid(name = name), name)
        }
    }
    @Test fun keepsZeroWidthJoinersInsideEmoji() {
        for (name in listOf("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67 Night", "\uD83C\uDFF3\uFE0F\u200D\uD83C\uDF08 Poker", "\uD83D\uDC69\uD83C\uDFFD\u200D\uD83D\uDCBB club")) {
            assertNull(invalid(name = name), name)
        }
    }
    @Test fun validatesBuyInAndSeats() {
        assertEquals("buyIn", invalid(buyIn = 99)); assertEquals("buyIn", invalid(buyIn = 1_000_000_001))
        assertEquals("maxSeats", invalid(seats = 1)); assertEquals("maxSeats", invalid(seats = 10))
        assertNull(invalid(buyIn = 100, seats = 2)); assertNull(invalid(buyIn = 1_000_000_000, seats = 9))
    }
    @Test fun validatesWeekday() { assertEquals("weekday", invalid(weekday = 0)); assertEquals("weekday", invalid(weekday = 8)) }
    @Test fun validatesQuarterHoursAndExactFormat() {
        for (time in listOf("8:00", "20:01", "24:00", "20:00:00")) assertEquals("localTime", invalid(time = time))
        for (time in listOf("00:00", "20:15", "20:30", "23:45")) assertNull(invalid(time = time))
    }
    @Test fun rejectsParsedFixedOffsetAliasesOutsideIanaRegistry() {
        for (zone in listOf("GMT+05:00", "UT+05:00")) {
            assertNotNull(TimeZone.of(zone))
            assertFalse(zone in TimeZone.availableZoneIds)
            assertEquals("timeZone", invalid(zone = zone))
        }
    }
    @Test fun acceptsNamedUtcAliasesAndIanaFixedOffsetZones() {
        for (zone in listOf("UTC", "GMT", "Etc/GMT+5")) {
            assertNull(invalid(zone = zone))
        }
    }
    @Test fun validatesZoneAndDuration() {
        for (zone in listOf("+05:00", "-04:00", "UTC+05:00", "Neverland")) assertEquals("timeZone", invalid(zone = zone))
        assertEquals("durationMinutes", invalid(duration = 29)); assertEquals("durationMinutes", invalid(duration = 481))
    }
}
