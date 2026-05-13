package com.dangerfield.cards.libraries.networking

import io.ktor.client.HttpClient

/**
 * Single source of pre-configured [HttpClient]s for the app. Repos and data
 * sources should depend on this — not build their own clients — so that JSON
 * config, default headers, base URL, logging, and auth all stay consistent.
 *
 * Two clients are exposed because the auth plugin runs on every request it's
 * installed on. Splitting them keeps unauthenticated calls (login, public
 * endpoints, healthchecks) free of token lookups + refresh churn.
 *
 * Both clients share the same engine, so connection pooling is unaffected.
 *
 * Wrap calls in `Catching { }` (from `:libraries:core`) at the call site —
 * Ktor throws on non-2xx + network errors and that's the model the rest of
 * the codebase already uses.
 */
interface NetworkClient {
    /** Use for unauthenticated requests (login, public endpoints, etc). */
    val client: HttpClient

    /**
     * Use for authenticated requests. Adds the bearer token from
     * [AuthTokenProvider] on every call and refreshes on 401.
     */
    val authenticatedClient: HttpClient
}
