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
 * Platform lookup for how the last run ended. Android reads
 * `ActivityManager.getHistoricalProcessExitReasons` (API 30+; older devices
 * report [PreviousExit.Unknown]). iOS reports [PreviousExit.Unknown]
 * unconditionally for now — the honest answer there needs MetricKit
 * (`MXAppExitMetric`) wiring, tracked under ENG-25 in `docs/todo.md`.
 */
interface PreviousExitProvider {
    fun previousExit(): PreviousExit
}
