package com.dangerfield.cards.libraries.telemetry.impl

import com.dangerfield.cards.libraries.core.Catching
import kotlin.time.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where the app believed it was the last time the OS handed it a lifecycle
 * callback.
 */
@Serializable
internal enum class RunState {
    Foreground,
    Background,
}

/**
 * The breadcrumb a run leaves behind for the next one. Rewritten on every
 * foreground/background transition and never explicitly cleared, because the
 * interesting deaths are the ones the process never sees coming — there is no
 * "we are about to be killed" callback to clear it from.
 */
@Serializable
internal data class RunMarker(
    val sessionId: String,
    val state: RunState,
    val updatedAtEpochSeconds: Long,
)

/**
 * How the previous run ended, judged by where the app was when it stopped
 * updating its [RunMarker]. This is the per-run signal iOS otherwise lacks:
 * `previous_exit` from MetricKit is a day-granular sample that lags a launch
 * (ENG-25), and Sentry's `WatchdogTermination` is a next-launch heuristic that
 * cannot separate a hang from a force-quit (ENG-42, CARDS-3).
 *
 * The separation this buys: swiping an app out of the iOS app switcher
 * backgrounds it *first*, so a force-quit lands as [BackgroundExit]. A run
 * that vanishes while it still believed it was on screen is a
 * [ForegroundTermination] — the candidate set for a real watchdog kill.
 *
 * [ForegroundTermination] is a candidate set, not a verdict. Hard crashes,
 * device power-off, and OS upgrades all land here too, so a rate is only
 * meaningful net of the crashes Sentry reports for the same
 * `previous_session_id`. Android carries the same marker on purpose: its
 * `ApplicationExitInfo` ground truth is the calibration for whether the iOS
 * reading can be trusted.
 */
enum class PreviousRunOutcome(val value: String) {
    ForegroundTermination("foreground_termination"),
    BackgroundExit("background_exit"),
    Unknown("unknown"),
}

/**
 * What the marker left behind says about the run before this one. [sessionId]
 * is the join key back into that run's own events in Loki — the route it died
 * on, its last breadcrumb, whether it ever reached a tap — so the marker
 * itself stays lean enough to write on a lifecycle callback.
 */
internal data class PreviousRun(
    val outcome: PreviousRunOutcome,
    val sessionId: String?,
    val ageSeconds: Long?,
)

/**
 * Synchronous, kill-durable slot for the serialized [RunMarker].
 *
 * Deliberately not DataStore, which every other persisted thing in the app
 * uses: the marker's entire job is to already be on disk when the OS kills the
 * process without warning, and an async store loses precisely the write that
 * matters. iOS binds `NSUserDefaults`, Android `SharedPreferences.commit()`.
 */
interface RunMarkerStore {
    fun read(): String?
    fun write(value: String?)
}

/**
 * Reads the previous run's marker, then keeps the current run's up to date.
 *
 * Storage is injected rather than assumed so the state machine stays
 * platform-free and testable, matching [LatestExitReport]. An unreadable
 * marker (corrupt, or written by a schema we no longer understand) degrades to
 * [PreviousRunOutcome.Unknown] rather than throwing — the same value every
 * install reports on its first launch after picking this up, since there is no
 * prior marker to read.
 */
internal class RunOutcomeTracker(
    private val store: RunMarkerStore,
    private val clock: Clock,
) {

    fun consumePreviousRun(): PreviousRun {
        val marker = readMarker() ?: return PreviousRun(PreviousRunOutcome.Unknown, null, null)
        return PreviousRun(
            outcome = when (marker.state) {
                RunState.Foreground -> PreviousRunOutcome.ForegroundTermination
                RunState.Background -> PreviousRunOutcome.BackgroundExit
            },
            sessionId = marker.sessionId,
            // A negative age means the wall clock moved backwards between the
            // two runs, which CARDS-3 shows happening for real
            // (SIGNIFICANT_TIME_CHANGE). Report nothing rather than nonsense.
            ageSeconds = (clock.now().epochSeconds - marker.updatedAtEpochSeconds).takeIf { it >= 0 },
        )
    }

    fun mark(sessionId: String, state: RunState) {
        Catching {
            store.write(json.encodeToString(RunMarker(sessionId, state, clock.now().epochSeconds)))
        }
    }

    private fun readMarker(): RunMarker? {
        val stored = store.read() ?: return null
        return Catching { json.decodeFromString<RunMarker>(stored) }.getOrNull()
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
