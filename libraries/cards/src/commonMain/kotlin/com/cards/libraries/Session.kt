package com.dangerfield.cards.libraries.cards

import kotlinx.coroutines.flow.Flow

/**
 * A user-perceptible app session.
 *
 * Boundaries (when the [id] rolls over):
 *  - Cold start of the process → session #1.
 *  - App backgrounded for ≥ [SessionTracker.BACKGROUND_ROLLOVER] and then
 *    foregrounded → next session id.
 *
 * Anything shorter than the background threshold stays the same session
 * — quick tab-switches, brief notification interactions, etc.
 *
 * **What this is for:** repositories that want "fetch once per session,
 * then trust the cache" semantics. The shop catalog is the first
 * adopter: on disk, refreshed when [SessionTracker.observe] emits a new
 * id and the persisted `lastFetchSessionId` doesn't match. Pull-to-
 * refresh ignores the session entirely.
 *
 * **What this is NOT:**
 *  - A persisted identifier across process restarts. Each cold boot
 *    starts at id #1 — sessions are process-local. Use [AppData
 *    .lastSessionEndedAt] for cross-launch "have you ever opened this
 *    app" signals; that's a separate concern.
 *  - A user-identity session. Auth state has its own lifecycle.
 */
data class Session(
    /**
     * Monotonic per-process counter, starting at `1` on cold boot and
     * incrementing on each background-rollover. Process restart resets
     * to `1`; **never** compare ids across restarts.
     */
    val id: Long,
    val startedAtMs: Long,
    val reason: SessionStartReason,
    /**
     * Globally-unique id for this session, fresh per boundary. Unlike
     * [id] (a 1,2,3… per-process counter that collides across devices),
     * this is a UUID safe to use as a **cross-system correlation key**:
     * it's stamped on the crash-reporting scope and sent to the backend
     * via `X-Session-Id`, so a single value pulls this session's frontend
     * events (Sentry) and backend traces/logs (Tempo/Loki). See
     * [SessionTracker] and `SessionIdProvider`.
     */
    val uuid: String,
)

sealed interface SessionStartReason {
    /** First session of this process. */
    data object ColdBoot : SessionStartReason

    /**
     * The previous session ended via background, and at least
     * [SessionTracker.BACKGROUND_ROLLOVER] elapsed before the user
     * came back. [backgroundedForMs] is the actual gap.
     */
    data class BackgroundRollover(val backgroundedForMs: Long) : SessionStartReason
}

/**
 * App-scoped view of the current [Session]. Listens to
 * [AppEventListener] under the hood; consumers should never have to
 * subscribe to the raw lifecycle themselves.
 *
 * Subscribe to [observe] for "do something when the session rolls" —
 * the flow replays the current session on subscribe so cold-init
 * consumers don't miss the first boundary.
 */
interface SessionTracker {
    /** The session currently in progress. Always non-null after cold boot. */
    val current: Session

    /**
     * Hot flow of session boundaries. Replays the current session to
     * new subscribers so a repository that subscribes mid-session
     * still gets a one-time "current session is N" signal it can
     * branch on.
     */
    fun observe(): Flow<Session>

    companion object {
        /**
         * How long the app must stay in the background before the
         * next foreground rolls the session. Mirrors common mobile-
         * analytics conventions (Apple's "session" attribution window
         * is 30 min; ours is tighter at 15 to match how we expect
         * data freshness to work — see decisions.md).
         */
        const val BACKGROUND_ROLLOVER_MS: Long = 15L * 60L * 1000L
    }
}
