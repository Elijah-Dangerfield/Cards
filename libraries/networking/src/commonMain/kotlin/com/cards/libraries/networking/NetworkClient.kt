package com.dangerfield.cards.libraries.networking

import io.ktor.client.HttpClient

/**
 * Single source of pre-configured [HttpClient]s for the app. Repos and data
 * sources should NOT touch [client] / [authenticatedClient] directly —
 * those are annotated [InternalNetworkingApi] and exist for the
 * `:libraries:networking` helpers themselves. Use one of:
 *
 *  - [authedCall] — authenticated request/response
 *  - [unauthedCall] — unauthenticated request/response (public endpoints)
 *  - [authedWebSocketSession] — authenticated WebSocket upgrade
 *
 * All three converge on the same code path: structured failure logging
 * keyed by `description`, optional [retry.RetryPolicy], and (for the authed
 * variants) a pre-flight [awaitAuthReady] so the bearer plugin doesn't burn
 * the request timeout waiting on a slow auth bootstrap.
 *
 * Wrap call-site decoding in `Catching { }` if you need to handle
 * deserialization separately from transport — Ktor throws on non-2xx +
 * network errors and the helpers above already wrap in [Catching].
 */
interface NetworkClient {
    /** Use for unauthenticated requests (login, public endpoints, etc). */
    @InternalNetworkingApi
    val client: HttpClient

    /**
     * Use for authenticated requests. Adds the bearer token from
     * `AuthTokenProvider.accessToken()` on every call and refreshes on 401.
     */
    @InternalNetworkingApi
    val authenticatedClient: HttpClient

    /**
     * Suspends until the auth subsystem has resolved a session (or
     * definitively can't). [authedCall] and [authedWebSocketSession] invoke
     * this before firing the request, so the request timeout clock starts
     * only after auth is ready — not during a slow cold-boot bootstrap.
     *
     * Public endpoints don't need to wait; [unauthedCall] skips this.
     */
    suspend fun awaitAuthReady()
}
