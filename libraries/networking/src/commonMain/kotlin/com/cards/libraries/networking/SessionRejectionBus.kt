package com.dangerfield.cards.libraries.networking

import kotlinx.coroutines.flow.Flow

/**
 * One-way signal from the token layer to the auth layer: the auth server has
 * **definitively rejected** our session — a 401 whose token refresh the server
 * itself rejected (token revoked, account deleted, refresh token invalid).
 *
 * Deliberately a bus, not a direct call into the auth repository, so
 * [AuthTokenProvider] stays narrow (it depends only on the token gateway, never
 * on the auth repository) and the construction graph stays linear. The auth
 * layer observes [rejections] and tears the dead session down.
 *
 * This is **not** the path for a transient 401 / timeout / 5xx / offline — those
 * keep the session and fall back to cache + an offline indicator. Only a
 * server-confirmed rejection flows through here, because acting on it is
 * destructive (a guest's progress is unrecoverable once the session is gone).
 */
interface SessionRejectionBus {

    /** A rejected session; [wasAnonymous] lets the auth layer route guest vs claimed. */
    data class Rejection(val wasAnonymous: Boolean)

    /** Fire-and-forget — called from the token-refresh path on a confirmed rejection. */
    fun signalRejected(wasAnonymous: Boolean)

    /** Observed by the auth layer to tear down the rejected session. */
    val rejections: Flow<Rejection>

    /**
     * Monotonic count of confirmed rejections this process. Bumped synchronously
     * at the top of [signalRejected] — which runs inside the bearer plugin's
     * token refresh, i.e. strictly *before* the failing request's final 401 can
     * propagate — so [authedCall] can compare before/after a failed request and
     * classify that 401 as session-death rather than a transient auth hiccup.
     * Defaulted so test fakes that never reject compile unchanged.
     */
    val rejectionEpoch: Long get() = 0
}
