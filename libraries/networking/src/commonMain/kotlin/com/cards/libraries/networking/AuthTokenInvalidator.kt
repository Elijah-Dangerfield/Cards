package com.dangerfield.cards.libraries.networking

/**
 * Clears the cached bearer token so the next authenticated request reloads it
 * from [AuthTokenProvider].
 *
 * Ktor's bearer auth plugin caches the token returned by `loadTokens` and only
 * re-fetches it via `refreshTokens` on a 401. That's wrong for a **session
 * switch** where the *old* token is still valid — e.g. signing in with Apple as
 * an anonymous guest swaps the session to a real account, but the previous
 * anon token doesn't 401, so Ktor keeps sending it and the server keeps
 * returning the old (anonymous) profile. The auth layer calls [invalidate] on
 * every session change so the stale token is dropped immediately.
 */
interface AuthTokenInvalidator {
    fun invalidate()
}
