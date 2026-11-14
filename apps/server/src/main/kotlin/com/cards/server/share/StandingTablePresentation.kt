package com.dangerfield.cards.server.share

import com.dangerfield.cards.server.db.toJavaInstant
import com.dangerfield.cards.server.domain.StandingTable
import com.dangerfield.cards.server.domain.nextStart
import java.net.URLEncoder
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

internal fun weekdayName(table: StandingTable): String =
    java.time.DayOfWeek.of(table.weekday.ordinal + 1).getDisplayName(TextStyle.FULL, Locale.ENGLISH)

internal fun timeAndZone(table: StandingTable, now: Instant): String {
    val time = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
        .format(LocalTime.of(table.localTime.hour, table.localTime.minute))
    val zone = DateTimeFormatter.ofPattern("z", Locale.ENGLISH)
        .withZone(ZoneId.of(table.timeZone.id)).format(nextStart(table, now).toJavaInstant())
    return "$time $zone"
}

// Recurrence uses the intended wall time even when the first date has a DST gap.
internal fun recurrenceStart(table: StandingTable, now: Instant): LocalDateTime =
    LocalDateTime(nextStart(table, now).toLocalDateTime(table.timeZone).date, table.localTime)

internal fun scheduleText(table: StandingTable, now: Instant): String =
    "${weekdayName(table)}s · ${timeAndZone(table, now)}"

internal fun googleCalendarUrl(table: StandingTable, shareUrl: String, now: Instant): String {
    val start = recurrenceStart(table, now)
    val end = start.toJavaLocalDateTime().plusMinutes(table.durationMinutes.toLong()).toKotlinLocalDateTime()
    val dates = localTimestamp(start) + "/" + localTimestamp(end)
    val parameters = linkedMapOf(
        "action" to "TEMPLATE",
        "text" to "${table.name} · Downcard",
        "dates" to dates,
        "recur" to "RRULE:FREQ=WEEKLY;BYDAY=${weeklyDay(table.weekday)}",
        "ctz" to table.timeZone.id,
        "details" to "Tap to join: $shareUrl\nRoom code ${table.code}",
        "location" to shareUrl,
    )
    return "https://calendar.google.com/calendar/render?" + parameters.entries.joinToString("&") {
        "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}"
    }
}
