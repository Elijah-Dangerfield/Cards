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
    // Primitive accessors keep the Swift side independent of Kotlin date/time interop.
    val startEpochMilliseconds: Long get() = firstStart.toEpochMilliseconds()
    val timeZoneId: String get() = timeZone.id
    val isoWeekday: Int get() = weekday.ordinal + 1
    val descriptionWithUrl: String get() = if (url in notes) notes else "$notes\n$url"
}

@ObjCName("CalendarLauncher", exact = true)
interface CalendarLauncher {
    suspend fun addWeekly(event: RecurringCalendarEvent): CalendarResult
}

@ObjCName("CalendarResult", exact = true)
enum class CalendarResult { Added, Cancelled, Unavailable }

/**
 * No BYDAY: Android's calendar editor evaluates the rule in the device's zone, so a
 * weekday taken from the table's zone lands on the wrong day for a friend whose
 * local date differs. A bare weekly rule repeats on the start instant's own weekday.
 */
const val WEEKLY_RECURRENCE_RULE = "FREQ=WEEKLY"
