package com.dangerfield.cards.server.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

fun nextStart(table: StandingTable, now: Instant): Instant {
    val earliest = now - table.durationMinutes.minutes
    var date = earliest.toLocalDateTime(table.timeZone).date
    repeat(8) {
        if (date.dayOfWeek == table.weekday) {
            val candidate = LocalDateTime(date, table.localTime).toInstant(table.timeZone)
            if (candidate >= earliest) return candidate
        }
        date = date.plus(1, DateTimeUnit.DAY)
    }
    error("No weekly start found")
}
