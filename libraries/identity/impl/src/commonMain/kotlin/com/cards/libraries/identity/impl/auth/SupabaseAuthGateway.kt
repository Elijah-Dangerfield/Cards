package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.identity.auth.OAuthProvider

/**
 * Thin abstraction over supabase-kt's `Auth` plugin. Exists so
 * [SupabaseAuthRepositoryImpl] can be unit-tested without booting a real
 * `SupabaseClient` — there are no first-class testing utilities for the
 * supabase-kt 3.x auth plugin (no in-memory client, no HTTP engine slot),
 * so the wrapper is our seam.
 *
 * Contract:
 *  - Read-only methods ([currentStatus], [currentSession]) are non-suspending
 *    and observe whatever state supabase-kt currently holds. They never
 *    throw.
 *  - Mutating methods are suspending and propagate exceptions raw —
 *    `RestException`, `HttpRequestException`, and other supabase-kt errors
 *    surface unchanged so [SupabaseAuthRepositoryImpl] can map them to its
 *    `*Outcome` sealed hierarchies. The wrapper is intentionally NOT a
 *    place where errors get translated.
 *
 * Production impl: [RealSupabaseAuthGateway] (wraps `supabase.auth.*`).
 * Test impl: `FakeSupabaseAuthGateway` in commonTest.
 */
interface SupabaseAuthGateway {

    /** Suspends until supabase-kt has loaded any persisted session. */
    suspend fun awaitInitialization()

    /** Snapshot of supabase-kt's current `SessionStatus`. Read once per resolve pass. */
    fun currentStatus(): AuthGatewayStatus

    /** Snapshot of the current Supabase session, or null if none. */
    fun currentSession(): GatewaySession?

    /** Sign in as an anonymous Supabase user. Throws on failure. */
    suspend fun signInAnonymously()

    /** Force-refresh the current session. Throws on failure. */
    suspend fun refreshSession()

    /** Email/password sign-in. Throws on failure. */
    suspend fun signInWithEmail(email: String, password: String)

    /** Email/password sign-up. Throws on failure. Supabase sends a verification email. */
    suspend fun signUpWithEmail(email: String, password: String)

    /** Resend the verification email for an unconfirmed sign-up. Throws on failure. */
    suspend fun resendVerificationEmail(email: String)

    /** Trigger Supabase's password-reset email. Throws on failure. */
    suspend fun resetPasswordForEmail(email: String)

    /** Tear down the current session. Throws on failure. */
    suspend fun signOut()

    /**
     * Attach an OAuth identity to the *current* user (typically anonymous).
     * Throws on failure or user cancellation.
     */
    suspend fun linkOAuthIdentity(provider: OAuthProvider)

    /**
     * Launch the browser OAuth flow for [provider]. This only *opens* the
     * system browser — the session doesn't exist yet when this returns. The
     * provider redirects back to `cards://login-callback`, which the app feeds
     * to [completeOAuthRedirect]. Throws if the browser can't be launched.
     */
    suspend fun signInWithOAuth(provider: OAuthProvider)

    /**
     * Finish a browser OAuth **sign-in** from the redirect deep link. [url] is
     * the full `cards://login-callback#access_token=…` URL the provider handed
     * back. Parses the session tokens out of the URL fragment (IMPLICIT flow)
     * and imports them into supabase-kt so [currentSession] reflects the new
     * user. Throws if the URL carries no valid session (e.g. the user
     * cancelled).
     *
     * Sign-in only — a **link** (claim) redirect carries no session fragment;
     * use [refreshLinkedUser] to fold the now-linked identity into the existing
     * session instead.
     */
    suspend fun completeOAuthRedirect(url: String)

    /**
     * Refresh the user attached to the *current* session from the server,
     * updating [sessionStatus] in place. Called after a browser **link** (claim)
     * redirect returns: the identity was attached server-side but the local
     * session is stale (still `is_anonymous=true`, no identities), and a link
     * redirect carries no session fragment to import. After this, [currentSession]
     * reflects the now-claimed, non-anonymous user. Throws on failure.
     */
    suspend fun refreshLinkedUser()

    /**
     * Whether [url] is the OAuth redirect deep link this gateway expects
     * (`cards://login-callback`). Lets the deep-link collector route auth
     * callbacks here and everything else to the nav graph, without the app
     * layer hardcoding the scheme/host.
     */
    fun isOAuthRedirect(url: String): Boolean

    /**
     * Native Sign in with Apple — exchange the Apple id token (+ the raw nonce
     * that was hashed into the Apple request) for a Supabase session. Throws on
     * failure / cancellation.
     */
    suspend fun signInWithAppleIdToken(idToken: String, nonce: String)

    /** Attach a native Apple identity to the current (anonymous) user. Throws on failure. */
    suspend fun linkAppleIdToken(idToken: String, nonce: String)

    /**
     * Native Google id-token sign-in — exchange a Google id token for a Supabase
     * session. [nonce] is optional (Credential Manager may not supply one).
     * Throws on failure. Wired ahead of a Google token source landing.
     */
    suspend fun signInWithGoogleIdToken(idToken: String, nonce: String?)

    /**
     * Attach an email/password identity to the current anonymous user.
     * Throws on failure.
     */
    suspend fun linkEmailIdentity(email: String, password: String)
}

/**
 * Wrapper for supabase-kt's `SessionStatus` — same four branches, our
 * own type so [SupabaseAuthRepositoryImpl] doesn't depend on supabase
 * symbols.
 */
sealed interface AuthGatewayStatus {
    /** Supabase-kt is still hydrating the persisted session. */
    data object Initializing : AuthGatewayStatus

    /** No session — caller should sign in (anonymous, or otherwise). */
    data object NotAuthenticated : AuthGatewayStatus

    /** A live session exists. The actual session lives on [GatewaySession]. */
    data object Authenticated : AuthGatewayStatus

    /**
     * Supabase-kt tried to refresh and failed — usually a transient network
     * issue. Treat as a "loop and re-poll" hint, same as [Initializing].
     */
    data class RefreshFailure(val cause: Throwable?) : AuthGatewayStatus
}

/**
 * Snapshot of a Supabase session, projected onto the fields
 * [SupabaseAuthRepositoryImpl] actually uses. Anonymity is computed
 * inside the gateway (no `identities` => anonymous) so the impl never
 * touches a supabase-kt user object.
 */
data class GatewaySession(
    val userId: String,
    val email: String?,
    val accessToken: String,
    val isAnonymous: Boolean,
    /**
     * Whether the user has confirmed their email via the verification
     * link. Always true for anonymous accounts (no email to confirm) and
     * false for sign-up flows pending verification.
     */
    val isEmailConfirmed: Boolean,
)
