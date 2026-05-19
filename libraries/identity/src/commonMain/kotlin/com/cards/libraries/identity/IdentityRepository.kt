package com.dangerfield.cards.libraries.identity

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first

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
    /**
     * Hot, cached identity state. Always present (StateFlow has a value).
     *
     * Lifecycle:
     *  - Briefly [IdentityState.Unknown] right after app construction,
     *    before the disk-cache hydrate fires.
     *  - Flips to [IdentityState.SignedIn] once either the cache or
     *    [ensureInitialized] resolves — whichever comes first.
     *  - Stays [SignedIn] for the rest of the process lifetime; a
     *    successful [signOut] clears the local cache and the next
     *    bootstrap mints a fresh anon identity. (There's no terminal
     *    `SignedOut` state — anon-by-default means we're always
     *    transitioning toward a new SignedIn rather than sitting in
     *    a signed-out limbo.)
     *
     * Feature code that runs post-onboarding should treat [SignedIn] as
     * the steady state. Use [awaitIdentity] / [currentIdentity] (in this
     * file) rather than reinventing `.filterIsInstance<SignedIn>().first()`
     * at the call site.
     */
    val state: StateFlow<IdentityState>

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
    /**
     * Patch the profile on the server. Each field is independent:
     * - `null` for a string field = leave it alone.
     * - Setting [avatarBackgroundColor] to a non-null hex sets it.
     * - Setting [clearAvatarBackgroundColor] = true clears the color
     *   back to the theme default. Tri-state via two args because JSON
     *   can't distinguish "missing" from "null" on the wire.
     */
    suspend fun updateProfile(
        displayName: String? = null,
        avatarEmoji: String? = null,
        avatarBackgroundColor: String? = null,
        clearAvatarBackgroundColor: Boolean = false,
    ): UpdateProfileOutcome

    /** Fetch the curated starter emoji pack so the avatar picker can render. */
    suspend fun fetchAvatarPack(): AvatarPackOutcome

    /**
     * Permanently delete the current account. Calls the server's
     * `DELETE /v1/me` (which in turn invokes Supabase's Admin API to
     * remove `auth.users` plus drops the local profile row) and then
     * tears down the local session.
     *
     * Idempotent for the caller: a previous successful delete that left
     * a stale local cache will resolve to [DeleteAccountOutcome.Success]
     * via the server's 204 path. The post-delete state is identical to
     * sign-out — callers should reset onboarding and navigate to the
     * onboarding root.
     */
    suspend fun deleteAccount(): DeleteAccountOutcome

    /**
     * Attach an Apple/Google identity to the current (typically anonymous)
     * Supabase user. Preserves chips, XP, and history. Uses supabase-kt's
     * `linkIdentity(provider)` which opens an OS browser for the OAuth
     * dance and returns when the redirect resolves.
     *
     * Fails with [LinkIdentityOutcome.AlreadyOnAnotherAccount] when the
     * OAuth identity is already attached to a different `auth.users` row;
     * the UI should offer "sign in there instead" (see [signInWithOAuth]).
     */
    suspend fun linkOAuthIdentity(provider: OAuthProvider): LinkIdentityOutcome

    /**
     * Switch sessions to an existing OAuth account. The current local
     * session (anonymous or otherwise) is replaced; any guest progress
     * tied to the previous session is orphaned by design (per the
     * 2026-05-18 "Identity pivot (REVERSED)" decision).
     *
     * UI should explicitly confirm the loss-of-guest-progress trade-off
     * before invoking this — pair with a "this will replace your current
     * progress" dialog.
     */
    suspend fun signInWithOAuth(provider: OAuthProvider): SignInOutcome
}

/**
 * Third-party identity providers we surface in V1. Both go through
 * Supabase Auth's OAuth flow (`supabase.auth.signInWith(provider)` and
 * `linkIdentity(provider)`); the dashboard's Providers tab must enable
 * each one and supply credentials before either flow works at runtime.
 */
enum class OAuthProvider { Google, Apple }

sealed interface LinkIdentityOutcome {
    data class Success(val identity: Identity) : LinkIdentityOutcome
    /** The OAuth identity is already attached to another auth.users row. */
    data object AlreadyOnAnotherAccount : LinkIdentityOutcome
    /** The current local session was anonymous and Supabase rejected linking. */
    data object NotSignedIn : LinkIdentityOutcome
    /** The user dismissed the OAuth browser tab before completing the flow. */
    data object Cancelled : LinkIdentityOutcome
    /** Provider not enabled in the Supabase dashboard. */
    data object ProviderNotEnabled : LinkIdentityOutcome
    data class NetworkError(val cause: Throwable) : LinkIdentityOutcome
    data class Unknown(val cause: Throwable) : LinkIdentityOutcome
}

sealed interface DeleteAccountOutcome {
    data object Success : DeleteAccountOutcome
    /** Server reports the delete endpoint isn't wired (no service-role key). */
    data object NotConfigured : DeleteAccountOutcome
    data object NotSignedIn : DeleteAccountOutcome
    data class NetworkError(val cause: Throwable) : DeleteAccountOutcome
    data class Unknown(val cause: Throwable) : DeleteAccountOutcome
}

sealed interface UpdateProfileOutcome {
    data class Success(val identity: Identity) : UpdateProfileOutcome
    data object DisplayNameTaken : UpdateProfileOutcome
    data object InvalidDisplayName : UpdateProfileOutcome
    data object InvalidAvatarEmoji : UpdateProfileOutcome
    data object InvalidAvatarBackgroundColor : UpdateProfileOutcome
    data object NotSignedIn : UpdateProfileOutcome
    data class NetworkError(val cause: Throwable) : UpdateProfileOutcome
    data class Unknown(val cause: Throwable) : UpdateProfileOutcome
}

/**
 * An emoji pack the user can pick avatars from. Always includes a
 * starter pack (`unlockProductId == null`); premium packs appear once
 * the matching product is in inventory. The server is authoritative —
 * the client never invents a pack.
 */
data class AvatarPack(
    val id: String,
    val name: String,
    val emojis: List<String>,
)

sealed interface AvatarPackOutcome {
    /**
     * Packs available to this user, in server-determined order. The
     * starter pack is always first; premium packs follow as they're
     * unlocked.
     *
     * [palette] is the curated set of avatar-background hex colors the
     * picker can render. Empty list = "no per-user color customization
     * available" (the client should hide the color picker section).
     */
    data class Success(
        val packs: List<AvatarPack>,
        val palette: List<String> = emptyList(),
    ) : AvatarPackOutcome
    data class NetworkError(val cause: Throwable) : AvatarPackOutcome
    data class Unknown(val cause: Throwable) : AvatarPackOutcome
}

sealed interface SignInOutcome {
    data class Success(val identity: Identity) : SignInOutcome
    /** Email/password didn't match an account, password wrong, etc. */
    data object InvalidCredentials : SignInOutcome
    /** Email exists but `email_confirmed_at` is null — must verify first. */
    data class EmailNotConfirmed(val email: String) : SignInOutcome
    /** User dismissed the OAuth browser tab before completing the flow. */
    data object Cancelled : SignInOutcome
    /** OAuth provider not enabled in the Supabase dashboard. */
    data object ProviderNotEnabled : SignInOutcome
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

/**
 * Latest known identity, or `null` if the bootstrap hasn't resolved yet
 * (a brief window on cold start before the disk cache hydrates). Synchronous —
 * doesn't suspend, doesn't observe.
 *
 * Use this when you need identity "now" and are willing to handle null
 * (typically: post-onboarding feature code that null-coalesces to defaults
 * for the rare race-window frame).
 */
val IdentityRepository.currentIdentity: Identity?
    get() = (state.value as? IdentityState.SignedIn)?.identity

/**
 * Suspend until [IdentityRepository.state] reaches [IdentityState.SignedIn]
 * and return the [Identity]. Returns immediately if already signed in.
 *
 * Safe to call from any post-onboarding code: by construction, by the time
 * a feature ViewModel constructs, either the cache has hydrated or
 * [IdentityRepository.ensureInitialized] is mid-flight — both terminate in
 * a `SignedIn` state. There is no scenario where this suspends forever
 * absent process death mid-call.
 *
 * Note: `state` is a [StateFlow], so the underlying `.first()` never
 * throws [NoSuchElementException] (StateFlow has no terminal state). It
 * propagates [kotlinx.coroutines.CancellationException] cooperatively when
 * the surrounding scope is torn down — which is the desired behavior, not
 * an error.
 */
suspend fun IdentityRepository.awaitIdentity(): Identity =
    state.filterIsInstance<IdentityState.SignedIn>().first().identity
