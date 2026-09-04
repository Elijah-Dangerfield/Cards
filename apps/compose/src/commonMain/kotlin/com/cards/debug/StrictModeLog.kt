package com.dangerfield.cards.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One StrictMode violation, collapsed to the thing worth acting on.
 *
 * [signature] is the identity: the violation class plus the first stack frame
 * inside our own package. Everything else about a violation — the timestamp,
 * the rest of the trace — differs between two reports of the same underlying
 * mistake, so keying on those would show the same bug a hundred times.
 */
data class StrictModeViolation(
    val signature: String,
    /** The violation type, e.g. `DiskReadViolation`. */
    val kind: String,
    /** First stack frame inside app code, which is the line to go fix. */
    val origin: String,
    /** How many times it has fired since the app started. */
    val count: Int,
    /** False until acknowledged, which is what drives the badge and the toast. */
    val isNew: Boolean,
)

/**
 * Records StrictMode violations for the on-device QA menu.
 *
 * `penaltyLog()` writes to logcat, which is only useful if you happen to be
 * looking at logcat. The point of this is the opposite: it puts a violation in
 * front of you *without* being asked, but only once per distinct violation, so
 * it stays worth reading.
 *
 * **"New" means never seen in any previous run**, not new since launch. The
 * question worth answering is "did this build introduce a performance problem",
 * and a violation that has fired on every run for a month is not that. Seen
 * signatures persist across launches; the Android implementation owns that
 * storage because the whole feature is Android-and-debug-only and pushing it
 * through shared persistence would buy nothing.
 *
 * Debug builds only. In release, and on iOS, this is [NoOpStrictModeLog].
 */
interface StrictModeLog {

    /** Everything seen this run, worst-offending first. */
    val violations: StateFlow<List<StrictModeViolation>>

    /**
     * How many distinct violations have never been seen before. Drives the
     * badge on the shake menu and the one-time toast.
     */
    val newViolationCount: StateFlow<Int>

    /**
     * Marks everything currently listed as seen, so it stops counting as new
     * on the next launch. Called when the log screen is opened — reading the
     * list is the acknowledgement.
     */
    fun markAllSeen()
}

/** Release builds and iOS. */
class NoOpStrictModeLog : StrictModeLog {
    override val violations: StateFlow<List<StrictModeViolation>> = MutableStateFlow(emptyList())
    override val newViolationCount: StateFlow<Int> = MutableStateFlow(0)
    override fun markAllSeen() = Unit
}
