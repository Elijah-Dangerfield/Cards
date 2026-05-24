package com.dangerfield.cards.libraries.cards.impl.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity

/**
 * Pretty stdout writer for development visibility, in addition to whatever
 * platform writer Kermit has already installed (OSLogWriter on iOS,
 * LogcatWriter on Android).
 *
 * **Why we need this on top of the platform writer:** Android Studio's KMM
 * plugin filters `os_log` Debug entries out of its Run window — only
 * Info+ surfaces. So when you run an iOS app from AS, anything `KLog.d` /
 * `KLog.v` simply doesn't appear there even though it shows in Xcode's
 * console. Routing those low-severity entries through `println` → stdout
 * bypasses the plugin's filter (stdout has no level filter) and they show
 * up alongside the higher-severity entries.
 *
 * **Why only Debug-and-below:** Info+ already surfaces in the AS Run
 * window via the platform writer's natural path (LogcatWriter on Android,
 * OSLogWriter on iOS — Info+ entries from os_log aren't filtered by the
 * AS plugin, only Debug is). Letting this writer handle those too would
 * print every Info+ entry twice in Xcode console / Android logcat. Capping
 * at Debug means each entry shows once in the user's primary view
 * (AS Run window) and dupes only land at Debug level in secondary views
 * (Xcode console, Logcat at low filter levels) — the acceptable trade.
 *
 * **Format:** a compact one-liner like `🐛 NetworkCall • inventory.sync
 * failed (timeout)`. No timestamp / PID — those come from os_log on iOS
 * and from android.util.Log on Android automatically; the platform
 * writer's output already carries them where they're useful.
 */
class DevConsoleWriter : LogWriter() {

    override fun isLoggable(tag: String, severity: Severity): Boolean =
        severity <= Severity.Debug

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val sigil = when (severity) {
            Severity.Verbose -> "💭"
            Severity.Debug -> "🐛"
            // Unreachable per [isLoggable], but be safe if Kermit ever
            // bypasses the filter.
            Severity.Info -> "ℹ️"
            Severity.Warn -> "⚠️"
            Severity.Error -> "🔥"
            Severity.Assert -> "🛑"
        }
        val tagPart = tag.takeIf { it.isNotBlank() }?.let { "$it • " } ?: ""
        println("$sigil $tagPart$message")
        throwable?.printStackTrace()
    }
}
