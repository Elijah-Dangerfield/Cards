package com.dangerfield.cards.libraries.identity

import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for client-side identity state and the auth
 * operations that produce it.
 *
 * `state` is a hot flow that starts as [IdentityState.Unknown], flips to
 * [IdentityState.SignedIn] once a session resolves (anonymous or
 * email/password), and stays cached across cold starts via local
 * persistent storage so the app works offline.
 *
 * The auth methods ([signInAnonymouslyIfNeeded], [signInWithEmail],
 * [signUpWithEmail], [refreshSession], [signOut]) all eventually settle
 * the `state` flow on success. They surface failure as typed return
 * values rather than throwing, so the UI can render specific error
 * messages without try/catch ceremony.
 *
 * Supabase is **not** mentioned anywhere in this interface — features
 * consume this API, not the underlying auth provider. Swapping providers
 * is a `:libraries:identity:impl` change.
 */
interface IdentityRepository {
    val state: Flow<IdentityState>

    /**
     * Onboarding "Get Started" path. Ensures a Supabase session exists
     * (anonymous if needed) and bootstraps the profile via `/v1/me`.
     * Idempotent — repeated calls during a session do no network work.
     *
     * Throws on network/server/auth failure. Callers should wrap in
     * `Catching { }` and surface a retry UI.
     */
    suspend fun ensureInitialized(): Identity

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
     * Tear down the current session and clear all local identity state.
     * Used by the "discard guest progress and sign in" confirmation flow.
     */
    suspend fun signOut()

    /**
     * Patch the profile on the server. Either field may be null (= leave
     * alone). On success the local cache + [state] flow flip to the new
     * values immediately.
     */
    suspend fun updateProfile(
        displayName: String? = null,
        avatarEmoji: String? = null,
    ): UpdateProfileOutcome

    /** Fetch the curated starter emoji pack so the avatar picker can render. */
    suspend fun fetchAvatarPack(): AvatarPackOutcome
}

sealed interface UpdateProfileOutcome {
    data class Success(val identity: Identity) : UpdateProfileOutcome
    data object DisplayNameTaken : UpdateProfileOutcome
    data object InvalidDisplayName : UpdateProfileOutcome
    data object InvalidAvatarEmoji : UpdateProfileOutcome
    data object NotSignedIn : UpdateProfileOutcome
    data class NetworkError(val cause: Throwable) : UpdateProfileOutcome
    data class Unknown(val cause: Throwable) : UpdateProfileOutcome
}

sealed interface AvatarPackOutcome {
    data class Success(val starter: List<String>) : AvatarPackOutcome
    data class NetworkError(val cause: Throwable) : AvatarPackOutcome
    data class Unknown(val cause: Throwable) : AvatarPackOutcome
}

sealed interface SignInOutcome {
    data class Success(val identity: Identity) : SignInOutcome
    /** Email/password didn't match an account, password wrong, etc. */
    data object InvalidCredentials : SignInOutcome
    /** Email exists but `email_confirmed_at` is null — must verify first. */
    data class EmailNotConfirmed(val email: String) : SignInOutcome
    /** Network down, Supabase unreachable, our server unreachable, etc. */
    data class NetworkError(val cause: Throwable) : SignInOutcome
    /** Anything we don't have a more specific bucket for. */
    data class Unknown(val cause: Throwable) : SignInOutcome
}

sealed interface SignUpOutcome {
    /** Account created. Verification email sent. Navigate to verify screen. */
    data class VerificationRequired(val email: String) : SignUpOutcome
    data object EmailAlreadyRegistered : SignUpOutcome
    data object WeakPassword : SignUpOutcome
    data object InvalidEmail : SignUpOutcome
    data class NetworkError(val cause: Throwable) : SignUpOutcome
    data class Unknown(val cause: Throwable) : SignUpOutcome
}

sealed interface RefreshOutcome {
    /** Session refreshed and email is confirmed. UI may advance to home. */
    data class EmailConfirmed(val identity: Identity) : RefreshOutcome
    /** Session refreshed but email still pending. UI stays on verify screen. */
    data object StillPending : RefreshOutcome
    /** Session was invalid / expired / revoked; UI should reset to sign-in. */
    data object SessionExpired : RefreshOutcome
    data class NetworkError(val cause: Throwable) : RefreshOutcome
    data class Unknown(val cause: Throwable) : RefreshOutcome
}

sealed interface ResendOutcome {
    data object Sent : ResendOutcome
    /** Supabase rate-limits the resend endpoint; user must wait. */
    data class RateLimited(val retryAfterSeconds: Int?) : ResendOutcome
    data class NetworkError(val cause: Throwable) : ResendOutcome
    data class Unknown(val cause: Throwable) : ResendOutcome
}
