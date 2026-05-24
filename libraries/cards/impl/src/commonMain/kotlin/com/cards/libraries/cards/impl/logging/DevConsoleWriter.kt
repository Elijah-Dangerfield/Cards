package com.dangerfield.cards.libraries.cards.impl.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.time.Clock

/**
 * Pretty stdout writer for development visibility, in addition to whatever
 * platform writer Kermit has already installed (OSLogWriter on iOS,
 * LogcatWriter on Android).
 *
 * **Why we need this on top of the platform writer:** Android Studio's KMM
 * plugin filters `os_log` Debug entries out of its Run window when running
 * iOS apps — so anything `KLog.d` / `KLog.v` simply doesn't appear there
 * even though Xcode console shows it. Routing those low-severity entries
 * through `println` → stdout bypasses the plugin's filter and they show
 * up alongside the higher-severity entries.
 *
 * **Why only Debug-and-below:** Info+ already surfaces in the AS Run
 * window via the platform writer's natural path. Letting this writer
 * handle those too would double-print them in Xcode console / Android
 * logcat.
 *
 * **Format** mirrors Apple's `os_log` console rendering so the two log
 * paths look visually identical at a glance:
 *
 *   `2026-05-24 10:57:43.465820-0400 Cards [] 🟢 (HomeViewModel) message`
 *
 * The full `os_log` line also carries `[pid:tid]` inside the process
 * brackets; we omit those (no `getpid` in commonMain Kotlin without
 * expect/actual platform code) and use an empty bracket as a placeholder.
 */
class DevConsoleWriter : LogWriter() {

    override fun isLoggable(tag: String, severity: Severity): Boolean =
        severity <= Severity.Debug

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val dot = when (severity) {
            Severity.Verbose,
            Severity.Debug,
            Severity.Info -> "🟢"
            Severity.Warn -> "🟡"
            Severity.Error -> "🔴"
            Severity.Assert -> "🟣"
        }
        println("${nowFormatted()} Cards [] $dot ($tag) $message")
        throwable?.printStackTrace()
    }
}

private val TIMESTAMP_FORMAT = LocalDateTime.Format {
    year()
    char('-')
    monthNumber()
    char('-')
    dayOfMonth()
    char(' ')
    hour()
    char(':')
    minute()
    char(':')
    second()
    char('.')
    secondFraction(6)
}

/** Matches Apple's `os_log` timestamp format, e.g. `2026-05-24 10:57:43.465820-0400`. */
private fun nowFormatted(): String {
    val now = Clock.System.now()
    val tz = TimeZone.currentSystemDefault()
    val local = now.toLocalDateTime(tz)
    val offsetSec = tz.offsetAt(now).totalSeconds
    val sign = if (offsetSec >= 0) "+" else "-"
    val absSec = abs(offsetSec)
    val hours = (absSec / 3600).toString().padStart(2, '0')
    val minutes = ((absSec % 3600) / 60).toString().padStart(2, '0')
    return "${local.format(TIMESTAMP_FORMAT)}$sign$hours$minutes"
}
