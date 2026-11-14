package com.dangerfield.cards.server.share

import com.dangerfield.cards.server.domain.StandingTable
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.Locale
import kotlin.time.Instant

object IcsBuilder {
    fun build(table: StandingTable, shareUrl: String, now: Instant): String {
        val start = recurrenceStart(table, now)
        val lines = listOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Downcard//Standing tables//EN",
            "CALSCALE:GREGORIAN",
            "BEGIN:VEVENT",
            "UID:${table.code}@downcard.app",
            "DTSTAMP:${utcTimestamp(now)}",
            "DTSTART;TZID=${table.timeZone.id}:${localTimestamp(start)}",
            "DURATION:PT${table.durationMinutes}M",
            "RRULE:FREQ=WEEKLY;BYDAY=${weeklyDay(table.weekday)}",
            "SUMMARY:${escape("${table.name} · Downcard")}",
            "URL:${escape(shareUrl)}",
            "DESCRIPTION:${escape("Tap to join: $shareUrl\nRoom code ${table.code}")}",
            "BEGIN:VALARM",
            "TRIGGER:-PT15M",
            "ACTION:DISPLAY",
            "DESCRIPTION:${escape("${table.name} starts in 15 minutes")}",
            "END:VALARM",
            "END:VEVENT",
            "END:VCALENDAR",
        )
        return lines.joinToString("\r\n", postfix = "\r\n") { fold(it) }
    }

    internal fun escape(value: String): String = value.replace("\\", "\\\\")
        .replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\\n")
        .replace(";", "\\;").replace(",", "\\,")

    internal fun fold(line: String): String = buildString {
        var octets = 0
        line.codePoints().forEach { codePoint ->
            val text = String(Character.toChars(codePoint))
            val size = text.toByteArray(Charsets.UTF_8).size
            if (octets + size > 75) {
                append("\r\n ")
                octets = 1
            }
            append(text)
            octets += size
        }
    }
}

internal fun weeklyDay(day: DayOfWeek): String =
    listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")[day.ordinal]

internal fun localTimestamp(time: LocalDateTime): String =
    "%04d%02d%02dT%02d%02d%02d".format(Locale.ROOT, time.year, time.monthNumber, time.dayOfMonth, time.hour, time.minute, time.second)

private fun utcTimestamp(now: Instant): String =
    localTimestamp(now.toLocalDateTime(TimeZone.UTC)) + "Z"
