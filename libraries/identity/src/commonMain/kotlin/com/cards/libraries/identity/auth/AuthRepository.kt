package com.dangerfield.cards.libraries.identity.auth

import kotlinx.coroutines.flow.Flow

/**
 * Owns the device's Supabase user lifecycle + access token.
 *
 * On construction, runs get-or-create: if no Supabase session exists,
 * signs in anonymously. After init resolves, [current] / [observe]
 * return either [AuthState.Authenticated] (happy path) or
 * [AuthState.Unauthenticated] (offline, anon sign-ins disabled, etc.).
 *
 * **No in-flight sentinel state.** [current] suspends until the answer
 * is real; [observe] emits only resolved values. UI that wants to render
 * a spinner should do so while waiting on its first emission.
 *
 * **Source of truth for the access token.** [accessToken] is what the
 * networking layer calls before attaching the bearer header. No separate
 * separate token provider — auth owns its token end-to-end.
 *
 * Errors from auth operations are returned as sealed outcome types
 * rather than thrown, because the UI wants to render specific messages
 * for "invalid credentials" vs "network down" vs "email already
 * registered." Try/catch at every call site was the worse alternative.
 */
interface AuthRepository {

    /**
     * Suspends until auth resolves to a definitive state. Idempotent —
     * concurrent callers share one in-flight resolve.
     */
    suspend fun current(): AuthState

    /**
     * Reactive stream of auth state changes. First emission lands after
     * the initial resolve completes. Subsequent emissions on sign-in,
     * sign-out, account delete, etc.
     */
    fun observe(): Flow<AuthState>

    /**
     * Supabase access token, ready for `Authorization: Bearer <token>`.
     * Suspends until [current] resolves. Returns null when the resolved
     * state is [AuthState.Unauthenticated] — the network layer attaches
     * no bearer and the request 401s cleanly.
     */
    suspend fun accessToken(): String?

    /**
     * Force-refresh the access token. Called by the networking layer's
     * bearer plugin on 401. Returns the new token, or null if refresh
     * failed (in which case the caller treats the request as unauth).
     *
     * supabase-kt auto-refreshes in the background anyway; this is the
     * "401 happened, get me a fresh one right now" path.
     */
    suspend fun refreshAccessToken(): String?

    /**
     * Re-attempt the get-or-create after a previous failure. Used by the
     * connectivity observer (offline → online flip) and explicit retry
     * actions (e.g. onboarding "Try again"). No-op if already
     * Authenticated.
     */
    suspend fun retry(): AuthState

    /** Email/password sign-in. Server-issued JWT replaces any current session. */
    suspend fun signInWithEmail(email: String, password: String): SignInOutcome

    /**
     * Email/password sign-up. Supabase sends a verification email; the
     * session is in a "pending email confirmation" state until the user
     * clicks the link AND we call [refreshSession]. Until then,
     * `/v1/me`-protected calls will succeed because the JWT itself is
     * valid — the verification gate is product-level.
     */
    suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome

    /**
     * Pulls the current Supabase session from the server. Used by the
     * "I clicked the verification link" button on the email-verification
     * screen to learn whether the user's email is now confirmed.
     */
    suspend fun refreshSession(): RefreshOutcome

    /** Resend the verification email for the current pending sign-up. */
    suspend fun resendVerificationEmail(email: String): ResendOutcome

    /**
     * Tear down the current Supabase session. The next [current] call
     * will trigger a fresh anonymous sign-in.
     */
    suspend fun signOut()

    /**
     * Permanently delete the current account. Calls the server's
     * `DELETE /v1/me` (which in turn invokes Supabase's Admin API to
     * remove `auth.users` plus drops the local profile row) and then
     * tears down the local Supabase session.
     */
    suspend fun deleteAccount(): DeleteAccountOutcome

    /**
     * Attach an Apple/Google identity to the current (typically anonymous)
     * Supabase user. Preserves chips, XP, and history.
     */
    suspend fun linkOAuthIdentity(provider: OAuthProvider): LinkIdentityOutcome

    /**
     * Switch sessions to an existing OAuth account. The current local
     * session is replaced; any guest progress tied to the previous
     * session is orphaned by design.
     */
    suspend fun signInWithOAuth(provider: OAuthProvider): SignInOutcome

    /**
     * Attach an email/password to the current anonymous Supabase user.
     * Triggers a verification email; the user is anonymous until they
     * click the link (see [refreshSession]).
     */
    suspend fun linkEmailIdentity(email: String, password: String): LinkEmailIdentityOutcome
}

/**
 * Resolved auth state. No in-flight sentinel — consumers suspend on
 * [AuthRepository.current] / observe the first [AuthRepository.observe]
 * emission instead.
 *
 * `Authenticated` covers both anonymous and claimed (email/OAuth) sessions
 * — the `isAnonymous` flag distinguishes when it matters (e.g. the
 * "claim your account" prompt).
 *
 * `Unauthenticated` is the fallback case: no Supabase session, no working
 * token, the network layer can't attach a bearer. Profile features fall
 * back to client-only state (`Profile.Fallback`). When connectivity
 * returns, [AuthRepository.retry] re-attempts.
 */
sealed interface AuthState {
    data class Authenticated(
        /** Supabase `auth.users.id`. The single source of user identity. */
        val userId: String,
        val isAnonymous: Boolean,
        val email: String?,
    ) : AuthState

    data class Unauthenticated(
        /** Why the last resolve failed. Null when nothing's been attempted. */
        val cause: Throwable? = null,
    ) : AuthState
}
