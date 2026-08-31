package com.dangerfield.cards.libraries.networking

import kotlinx.coroutines.flow.StateFlow

/**
 * The latched form of [AccessDeniedBus]. The bus announces the moment a `403`
 * with the locked envelope arrives; this remembers it, so the client can stop
 * sending requests that are already known to fail.
 *
 * Without the latch every subsequent call fired anyway, took a full round-trip
 * to be told the same thing, and logged an error on the way back (Sentry
 * CARDS-BG). [isBanned] is deliberately a plain synchronous read because the
 * request interceptor consults it on every call and can't suspend.
 *
 * In memory, not persisted: a relaunch fires one doomed request before the
 * first `403` re-latches it, which is one request rather than a stream, and a
 * persisted ban that the server later lifts would need its own invalidation
 * story. See `docs/agent/feedback-cases/ENG-35.md`.
 */
interface BannedState {

    /** Read by the request interceptor on every call — cheap and non-suspending. */
    val isBanned: Boolean

    /** The active denial, or null when access is fine. Drives the blocking screen. */
    val denial: StateFlow<AccessDeniedBus.Denial?>

    /** Latch a denial the response validator just decoded off a `403`. */
    fun setBanned(denial: AccessDeniedBus.Denial)

    /** Access is fine again — the allowlisted status probe came back `200`. */
    fun clearBanned()
}
