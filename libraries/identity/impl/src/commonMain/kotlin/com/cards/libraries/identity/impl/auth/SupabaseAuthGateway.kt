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

    /** Switch sessions to an existing OAuth account. Throws on failure. */
    suspend fun signInWithOAuth(provider: OAuthProvider)

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
