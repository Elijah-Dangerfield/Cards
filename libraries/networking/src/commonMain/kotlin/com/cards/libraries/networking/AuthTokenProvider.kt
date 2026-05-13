package com.dangerfield.cards.libraries.networking

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
     * Called by Ktor's auth plugin when a 401 comes back. Refresh and return the
     * new token, or null to treat the request as unauthenticated. Default: same
     * as [getAccessToken] (no refresh logic).
     */
    suspend fun refreshAccessToken(): String? = getAccessToken()
}
