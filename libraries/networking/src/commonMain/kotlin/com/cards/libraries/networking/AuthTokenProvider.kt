package com.dangerfield.cards.libraries.networking

import kotlin.time.Duration

/**
 * Source of bearer tokens for [NetworkClient.authenticatedClient].
 *
 * Bind a project-specific implementation in your auth feature's impl module
 * (`@ContributesBinding(AppScope::class)`). The default in
 * `:libraries:networking:impl` is a no-op that returns `null` so unauthenticated
 * apps work out of the box.
 */
interface AuthTokenProvider {
    /** Returns the current access token, or null if no user is signed in. */
    suspend fun getAccessToken(): String?

    /**
     * Suspends until a non-null access token is retrievable, or [timeout]
     * elapses. Returns the token (or `null` on timeout / never-available).
     *
     * Used by [NetworkClient.authenticatedClient]'s `loadTokens` so a request
     * issued during cold-boot identity resolution waits for the token to land
     * rather than firing without a bearer and eating a 401. Per-repo
     * `awaitIdentity()` becomes redundant once this gate is in place.
     */
    suspend fun awaitAccessToken(timeout: Duration): String? = getAccessToken()

    /**
     * Called by Ktor's auth plugin when a 401 comes back. Refresh and return the
     * new token, or null to treat the request as unauthenticated. Default: same
     * as [getAccessToken] (no refresh logic).
     */
    suspend fun refreshAccessToken(): String? = getAccessToken()
}
