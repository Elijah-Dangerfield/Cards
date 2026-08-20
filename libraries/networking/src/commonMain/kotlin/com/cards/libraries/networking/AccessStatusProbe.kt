package com.dangerfield.cards.libraries.networking

/**
 * Asks the server whether the caller is still blocked, by making the one
 * request the circuit breaker lets through while [BannedState.isBanned].
 *
 * The answer isn't returned — it arrives through the machinery that's already
 * running. A `200` clears [BannedState] from the response validator, a `403`
 * re-latches it through [AccessDeniedBus]. Callers observe [BannedState.denial]
 * and react to what it says.
 *
 * The contract lives beside [BannedState] because the breaker's allowlist is
 * what makes the probe possible; the implementation lives in the identity layer,
 * which owns the `/v1/me` call. Nothing here decides *when* to probe — today
 * that's the blocking screen's "check again" button.
 */
interface AccessStatusProbe {

    /** Fire the probe. Suspends until the round-trip settles; never throws. */
    suspend fun recheck()
}
