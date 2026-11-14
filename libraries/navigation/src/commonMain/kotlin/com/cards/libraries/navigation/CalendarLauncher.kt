@file:OptIn(ExperimentalObjCName::class)

package com.dangerfield.cards.libraries.navigation

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlin.time.Instant

@ObjCName("RecurringCalendarEvent", exact = true)
data class RecurringCalendarEvent(
    val title: String,
    val notes: String,
    val url: String,
    val firstStart: Instant,
    val durationMinutes: Int,
    val timeZone: TimeZone,
    val weekday: DayOfWeek,
    val alertMinutesBefore: Int = 15,
) {
    // Primitive accessors keep the Swift Twin independent of Kotlin date/time interop.
    val startEpochMilliseconds: Long get() = firstStart.toEpochMilliseconds()
    val timeZoneId: String get() = timeZone.id
    val isoWeekday: Int get() = weekday.ordinal + 1
}

@ObjCName("CalendarLauncher", exact = true)
interface CalendarLauncher {
    suspend fun addWeekly(event: RecurringCalendarEvent): CalendarResult
}

@ObjCName("CalendarResult", exact = true)
enum class CalendarResult { Added, Cancelled, Unavailable }

fun weeklyRule(weekday: DayOfWeek): String {
    val day = when (weekday) {
        DayOfWeek.MONDAY -> "MO"
        DayOfWeek.TUESDAY -> "TU"
        DayOfWeek.WEDNESDAY -> "WE"
        DayOfWeek.THURSDAY -> "TH"
        DayOfWeek.FRIDAY -> "FR"
        DayOfWeek.SATURDAY -> "SA"
        DayOfWeek.SUNDAY -> "SU"
    }
    return "FREQ=WEEKLY;BYDAY=$day"
}
