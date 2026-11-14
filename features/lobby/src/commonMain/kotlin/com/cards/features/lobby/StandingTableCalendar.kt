package com.dangerfield.cards.features.lobby

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

fun nextValidCalendarStart(firstStart: Instant, nominalTime: LocalTime, weekday: DayOfWeek, zone: TimeZone): Instant {
    var start = firstStart
    while (start.toLocalDateTime(zone).let { it.time != nominalTime || it.dayOfWeek != weekday }) {
        var nextDate = start.toLocalDateTime(zone).date.plus(1, DateTimeUnit.DAY)
        while (nextDate.dayOfWeek != weekday) nextDate = nextDate.plus(1, DateTimeUnit.DAY)
        start = LocalDateTime(nextDate, nominalTime).toInstant(zone)
    }
    return start
}
