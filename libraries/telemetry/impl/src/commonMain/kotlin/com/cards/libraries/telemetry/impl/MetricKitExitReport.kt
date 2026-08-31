package com.dangerfield.cards.libraries.telemetry.impl

import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.logEvent

/**
 * One MetricKit foreground-exit report (`MXForegroundExitData`), reduced to
 * the counts our `previous_exit` taxonomy can express. Counts cover the
 * payload's ~24h reporting window, not a single run — MetricKit has no
 * per-launch truth, so a report classifies to the most severe exit observed
 * in the window (a crash matters more than the clean exits around it).
 */
internal data class ForegroundExitCounts(
    val normal: Long = 0,
    val abnormal: Long = 0,
    val watchdog: Long = 0,
    val memoryLimit: Long = 0,
    val badAccess: Long = 0,
    val illegalInstruction: Long = 0,
) {
    fun classify(): PreviousExit = when {
        abnormal + badAccess + illegalInstruction > 0 -> PreviousExit.Crash
        watchdog > 0 -> PreviousExit.Anr
        memoryLimit > 0 -> PreviousExit.Oom
        normal > 0 -> PreviousExit.Clean
        else -> PreviousExit.Unknown
    }
}

/**
 * `app.exit_metrics` — the raw window counts, emitted once per MetricKit
 * payload. [ForegroundExitCounts.classify] throws away everything but the most
 * severe exit, which is right for a single `previous_exit` string and useless
 * for the question ENG-42 asks: how often does iOS watchdog-kill this app?
 * These counts are cumulative within the payload's ~24h window, so a dashboard
 * charts them as a rate over deliveries, not as a running total.
 */
internal fun logForegroundExitMetrics(counts: ForegroundExitCounts) {
    KLog.logEvent(
        "app.exit_metrics",
        "exit_normal" to counts.normal,
        "exit_abnormal" to counts.abnormal,
        "exit_watchdog" to counts.watchdog,
        "exit_memory_limit" to counts.memoryLimit,
        "exit_bad_access" to counts.badAccess,
        "exit_illegal_instruction" to counts.illegalInstruction,
        "classified_as" to counts.classify().value,
    )
}

/**
 * Consume-once handoff between the MetricKit subscriber (which learns how
 * runs ended, up to a day late) and `app.launched` (which reports it on the
 * next cold start). A report is surfaced by exactly one launch and then
 * cleared: re-reporting the same day-window on every subsequent launch would
 * multiply one crash by the user's launch frequency in the exit-rate
 * dashboards. Launches with no fresh report say `unknown`.
 *
 * Storage is caller-provided ([read]/[write]) so the state machine stays
 * platform-free and testable; iOS wires it to `NSUserDefaults`.
 */
internal class LatestExitReport(
    private val read: () -> String?,
    private val write: (String?) -> Unit,
) {
    fun record(counts: ForegroundExitCounts) {
        val classified = counts.classify()
        if (classified != PreviousExit.Unknown) {
            write(classified.value)
        }
    }

    fun consume(): PreviousExit {
        val stored = read() ?: return PreviousExit.Unknown
        write(null)
        return PreviousExit.entries.firstOrNull { it.value == stored } ?: PreviousExit.Unknown
    }
}
