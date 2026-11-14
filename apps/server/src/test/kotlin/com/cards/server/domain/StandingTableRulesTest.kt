package com.dangerfield.cards.server.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StandingTableRulesTest {
    private fun invalid(name: String = "Poker", buyIn: Long = 5000, seats: Int = 6, weekday: Int = 4,
                        time: String = "20:00", zone: String = "America/New_York", duration: Int = 120) =
        StandingTableRules.invalidField(name, buyIn, seats, weekday, time, zone, duration)
    @Test fun normalizesWhitespace() { assertEquals("Thursday Poker", StandingTableRules.normalizedName(" Thursday   Poker ")); assertNull(invalid()) }
    @Test fun rejectsNames() { for (name in listOf(" ", "x".repeat(33), "hi\nthere", "a\u0000b")) assertEquals("name", invalid(name = name)) }
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
    @Test fun validatesZoneAndDuration() {
        for (zone in listOf("+05:00", "-04:00", "UTC+05:00", "Neverland")) assertEquals("timeZone", invalid(zone = zone))
        assertEquals("durationMinutes", invalid(duration = 29)); assertEquals("durationMinutes", invalid(duration = 481))
    }
}
