package com.dangerfield.cards.libraries.networking

/**
 * Narrow contract the network layer needs from auth. Defined here (not in
 * `:libraries:identity`) so the networking layer doesn't transitively depend
 * on the auth state machine.
 *
 * Two responsibilities, deliberately split:
 *
 *  - [awaitReady] is the suspend boundary that drives auth bootstrap to
 *    completion. Called by [NetworkClient.awaitAuthReady] *before* a request
 *    fires, so the request's timeout clock doesn't run during a slow
 *    cold-boot resolve.
 *
 *  - [accessToken] is a peek — synchronous in spirit (still typed as
 *    suspend so impls aren't boxed into a fully-sync model). Called by
 *    Ktor's bearer plugin inside `loadTokens`, by which point [awaitReady]
 *    has already run and a token (or definitive null) is on hand.
 *
 * The split exists so the bearer plugin can't accidentally start a long
 * wait inside Ktor's request lifecycle — that was the cold-boot
 * timeout-eating bug we hit in production.
 */
interface AuthTokenProvider {

    /**
     * Suspends until the auth subsystem has resolved a session (or
     * definitively can't). Idempotent — concurrent callers share one
     * resolve and subsequent callers get the memoized result.
     */
    suspend fun awaitReady()

    /**
     * Current Supabase access token. Doesn't wait — call [awaitReady]
     * first if the result needs to reflect a resolved session. Null means
     * there's no session (anon sign-in disabled, offline before first
     * auth, post-signOut). The networking layer attaches no bearer and
     * the server 401s cleanly.
     */
    suspend fun accessToken(): String?

    /**
     * Force a session refresh and return the new token. Called by Ktor's
     * bearer plugin on 401. Null means the refresh failed and the caller
     * should treat the request as unauthed.
     */
    suspend fun refreshAccessToken(): String?

    /**
     * Whether the session behind [accessToken] is a guest one — the
     * `is_anonymous` claim, read off the same peek as the token. Needed when the
     * network layer discovers a dead session on its own (a server telling us the
     * account is gone) and has to say which kind died: the recovery screen offers
     * a guest a fresh start and a claimed account a sign-in. False when there's
     * no session at all, which can't reach a rejection path anyway.
     */
    suspend fun isAnonymousSession(): Boolean
}
