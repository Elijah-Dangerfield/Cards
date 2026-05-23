package com.dangerfield.cards.libraries.identity.profile

import kotlinx.coroutines.flow.Flow

/**
 * Owns the user's profile — display name, avatar, etc. Backed by
 * `/v1/me` on our server, mirrored in a local cache.
 *
 * Waits for [com.dangerfield.cards.libraries.identity.auth.AuthRepository]
 * to resolve before fetching: an authenticated session means
 * [Profile.Authenticated] from `/v1/me`; an unauthenticated state means
 * [Profile.Fallback] keyed on a stable client-generated UUID.
 *
 * **No in-flight sentinel.** [current] suspends until the answer is
 * real; [observe] emits only resolved profiles.
 *
 * **Cache as fallback, not first-frame.** The cache is consulted only
 * when `/v1/me` fails — the happy path is server-authoritative
 * end-to-end. Returning users on a healthy network see splash → home
 * with fresh data; returning users on an offline network fall back to
 * the cached profile via the `onFailure` branch.
 */
interface ProfileRepository {

    /**
     * Suspends until the profile resolves. Idempotent — concurrent
     * callers share one in-flight resolve.
     */
    suspend fun current(): Profile

    /**
     * Reactive. First emission after the initial resolve completes;
     * subsequent emissions on profile updates, auth changes, etc.
     * Never emits an "I have no value yet" sentinel.
     */
    fun observe(): Flow<Profile>

    /**
     * Patch the profile on the server. Each field is independent:
     * - `null` for a string field = leave it alone.
     * - Setting [avatarBackgroundColor] to a non-null hex sets it.
     * - Setting [clearAvatarBackgroundColor] = true clears the color
     *   back to the theme default. Tri-state via two args because JSON
     *   can't distinguish "missing" from "null" on the wire.
     */
    suspend fun update(
        displayName: String? = null,
        avatarEmoji: String? = null,
        avatarBackgroundColor: String? = null,
        clearAvatarBackgroundColor: Boolean = false,
    ): UpdateProfileOutcome

    /** Fetch the curated starter emoji pack so the avatar picker can render. */
    suspend fun fetchAvatarPack(): AvatarPackOutcome
}

/**
 * Resolved profile. Sealed because the "real Supabase-backed profile"
 * vs "client-only fallback" distinction is meaningful to most callers —
 * shop purchases hard-gate on [Authenticated], read-only browse surfaces
 * accept either.
 */
sealed interface Profile {
    /** Stable id usable as a foreign key for client-side state. */
    val id: String

    /**
     * Profile backed by a real Supabase user + a `/v1/me` row on our
     * server. `id` is the Supabase `auth.users.id`.
     */
    data class Authenticated(
        override val id: String,
        val displayName: String,
        val avatarEmoji: String,
        val avatarBackgroundColor: String?,
        val email: String?,
        val isAnonymous: Boolean,
    ) : Profile

    /**
     * Client-only fallback used when auth couldn't resolve AND there's
     * no cached profile. `id` is a UUID generated client-side and
     * persisted across launches so any local-only state (e.g. single-
     * player save) has a stable key.
     */
    data class Fallback(override val id: String) : Profile
}

sealed interface UpdateProfileOutcome {
    data class Success(val profile: Profile.Authenticated) : UpdateProfileOutcome
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
 * starter pack; premium packs appear once the matching product is in
 * inventory. The server is authoritative — the client never invents
 * a pack.
 */
data class AvatarPack(
    val id: String,
    val name: String,
    val emojis: List<String>,
)

sealed interface AvatarPackOutcome {
    /**
     * Packs available to this user, in server-determined order.
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
