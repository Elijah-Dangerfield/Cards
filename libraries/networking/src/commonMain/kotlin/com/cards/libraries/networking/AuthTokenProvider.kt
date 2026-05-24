package com.dangerfield.cards.libraries.networking

/**
 * Narrow contract the network layer needs from auth: a fresh access token, and a
 * refresh hook for 401s. Defined here (not in `:libraries:identity`) so the
 * networking layer doesn't transitively depend on the auth state machine —
 * `NetworkClient` only cares about bearer tokens, not sign-in outcomes or
 * account lifecycle. The identity layer ships an impl that talks to Supabase
 * via its gateway.
 *
 * Both methods are suspending and must wait for any in-flight auth resolution
 * (first-launch anon sign-in, persisted-session hydration) before answering.
 * Returning null is the explicit "no session — send the request unauthed and
 * let the server 401" signal.
 */
interface AuthTokenProvider {

    /**
     * Current Supabase access token, ready for `Authorization: Bearer <token>`.
     * Suspends until any in-flight resolve completes. Null means there's no
     * session (e.g. anon sign-in disabled at the project level, offline first
     * launch).
     */
    suspend fun accessToken(): String?

    /**
     * Force a session refresh and return the new token. Called by Ktor's bearer
     * plugin on 401. Null means the refresh failed and the caller should treat
     * the request as unauthed.
     */
    suspend fun refreshAccessToken(): String?
}
