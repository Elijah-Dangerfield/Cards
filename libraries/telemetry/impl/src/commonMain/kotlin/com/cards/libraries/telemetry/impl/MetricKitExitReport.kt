package com.dangerfield.cards.libraries.telemetry.impl

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
