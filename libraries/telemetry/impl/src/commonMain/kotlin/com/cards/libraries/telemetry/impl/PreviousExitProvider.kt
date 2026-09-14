package com.dangerfield.cards.libraries.telemetry.impl

/**
 * How the previous run of the app ended; rides on `app.launched` as the
 * `previous_exit` attribute so crash/ANR/OOM rates are queryable straight
 * from Loki next to the launch funnel.
 */
enum class PreviousExit(val value: String) {
    Clean("clean"),
    Crash("crash"),
    Anr("anr"),
    Oom("oom"),
    Unknown("unknown"),
}

/**
 * OS-reported process state at the moment of the previous exit — the
 * question `previous_exit=oom` alone can't answer: did the app blow its own
 * memory budget in active use, or did the OS reclaim an idle background
 * process for something else? Android's `ApplicationExitInfo` carries this
 * alongside the exit reason (API 30+): [importance] is the raw
 * `ActivityManager.RunningAppProcessInfo.IMPORTANCE_*` constant the process
 * held when it died (<=100 `IMPORTANCE_FOREGROUND`/`IMPORTANCE_VISIBLE`
 * means it was actually on screen; >=300 means it was backgrounded or
 * cached), [pssKb]/[rssKb] are its memory footprint in kilobytes, and
 * [description] is whatever free-text reason (often from lmkd) the OS
 * attached. iOS has no equivalent — MetricKit reports day-window counts,
 * not per-process memory at death — so this is always null there.
 */
data class PreviousExitDetail(
    val importance: Int?,
    val pssKb: Long?,
    val rssKb: Long?,
    val description: String?,
)

/**
 * Platform lookup for how the last run ended. Android reads
 * `ActivityManager.getHistoricalProcessExitReasons` (API 30+; older devices
 * report [PreviousExit.Unknown]). iOS derives it from MetricKit exit
 * reports, which are day-granular and lag a launch — see
 * `IosPreviousExitProvider` for the exact semantics.
 */
interface PreviousExitProvider {
    fun previousExit(): PreviousExit

    /** [PreviousExitDetail] alongside [previousExit], when the platform can say more. Android-only; null on iOS. */
    fun previousExitDetail(): PreviousExitDetail? = null
}
