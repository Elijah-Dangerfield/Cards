package com.dangerfield.cards.libraries.identity.profile

import kotlin.time.Instant
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

    /**
     * Whether the current session's account was **just created** on the server
     * (a brand-new account's first contact) — the authoritative discriminator
     * for SIGN-UP vs SIGN-IN, read once right after an identity auth. Sourced
     * from the server's `/v1/me` `isNewAccount` flag, latched inside the repo so
     * it survives the profile hydrate racing the caller (the flag is one-shot
     * server-side). Returns false when unauthenticated or on error — treat "not
     * sure" as returning, so a real returning user is never trapped in
     * onboarding. Replaces the best-effort `ChipsRepository.walletJustCreated`
     * proxy. Consuming: reading it resets the latch. Default false for fakes.
     */
    suspend fun resolveIsNewAccount(): Boolean = false

    /**
     * Reactive form of [resolveIsNewAccount] for observers that can't call a
     * suspend consume in a flow `combine` (the Home starter-grant gate). Emits
     * true once a `/v1/me` hydrate reports a brand-new account and stays true for
     * the session (reset on sign-out / account switch) — it is NOT consumed by
     * reads, so onboarding's [resolveIsNewAccount] and the Home welcome can both
     * observe the same latch. Duplicate starter-grant reveals are prevented by
     * the persisted "already shown" watermark, not by consuming this signal.
     * Default: a constant-false flow for fakes.
     */
    fun observeAccountJustCreated(): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)

    /** Fetch the curated starter emoji pack so the avatar picker can render. */
    suspend fun fetchAvatarPack(): AvatarPackOutcome

    /**
     * Attempt to flush a queued offline edit (see [UpdateProfileOutcome.Queued])
     * now, if a session is available. Best-effort + idempotent — a no-op when
     * nothing's queued or still offline. Driven by the warm-foreground /
     * connectivity-regained triggers so a stuck edit isn't stranded when auth
     * never re-emits (e.g. the session stayed valid but a PATCH failed). Default
     * no-op so test fakes needn't implement it.
     */
    suspend fun flushPendingEdits() {}

    /**
     * Rejections surfaced when a **queued** offline edit is flushed and the
     * server refuses it (the display name was taken while you were offline, or
     * is invalid). The optimistic value is reverted; a global surface shows the
     * user a "couldn't save your name" message so the silent revert isn't
     * confusing. Default empty so fakes needn't implement it.
     */
    fun observeEditRejections(): Flow<ProfileEditRejection> = kotlinx.coroutines.flow.emptyFlow()
}

/** Why a flushed offline edit was refused by the server. Drives a user message. */
enum class ProfileEditRejection {
    DisplayNameTaken,
    InvalidDisplayName,
    InvalidAvatarEmoji,
    InvalidAvatarBackgroundColor,
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
        /**
         * Server-issued wall-clock when the profile row was first
         * created. Stable across reloads + survives device-switch via
         * a claimed account. Useful for "member since" / "you've been
         * playing for N days" rendering — UI does
         * `Clock.System.now() - profile.createdAt` and gets a
         * [kotlin.time.Duration] back directly.
         */
        val createdAt: Instant,
    ) : Profile

    /**
     * Client-only fallback used when auth couldn't resolve AND there's
     * no cached server profile. `id` is a UUID generated client-side and
     * persisted across launches so any local-only state (e.g. single-
     * player save) has a stable key.
     *
     * May carry a **locally-chosen** identity — the name/avatar the user
     * picked during an offline onboarding (or an offline Edit Profile) that
     * hasn't reached the server yet. Surfaced so the app shows the user's
     * choice instead of a generic placeholder while session-less; it syncs to
     * the server once a session is established. Null fields = nothing chosen
     * yet (the UI falls back to "You" / a default avatar).
     *
     * `Fallback` still means "no confirmed server session," so callers that
     * hard-gate on a real account (shop purchases, server writes) keep checking
     * `is Authenticated` — they must not treat a populated Fallback as real.
     */
    data class Fallback(
        override val id: String,
        val displayName: String? = null,
        val avatarEmoji: String? = null,
        val avatarBackgroundColor: String? = null,
    ) : Profile
}

/**
 * Display name from either profile shape — the server-confirmed one when
 * [Profile.Authenticated], the locally-chosen one when [Profile.Fallback].
 * Null when no name is known yet (render "You" / a placeholder).
 *
 * Use these for *rendering* identity. Anything that gates on a real session
 * (purchases, server writes) must still match on `is Profile.Authenticated`.
 */
val Profile.displayNameOrNull: String?
    get() = when (this) {
        is Profile.Authenticated -> displayName
        is Profile.Fallback -> displayName
    }

val Profile.avatarEmojiOrNull: String?
    get() = when (this) {
        is Profile.Authenticated -> avatarEmoji
        is Profile.Fallback -> avatarEmoji
    }

val Profile.avatarBackgroundColorOrNull: String?
    get() = when (this) {
        is Profile.Authenticated -> avatarBackgroundColor
        is Profile.Fallback -> avatarBackgroundColor
    }

sealed interface UpdateProfileOutcome {
    data class Success(val profile: Profile.Authenticated) : UpdateProfileOutcome

    /**
     * The edit was applied **locally** and queued to sync when a session is
     * available — the offline / session-less case. The chosen name/avatar shows
     * immediately (optimistically) and is carried until a session is minted,
     * which applies it server-side. The caller treats this like a success: the
     * user's change "stuck," it just hasn't reached the server yet.
     */
    data object Queued : UpdateProfileOutcome
    data object DisplayNameTaken : UpdateProfileOutcome
    data object InvalidDisplayName : UpdateProfileOutcome
    data object InvalidAvatarEmoji : UpdateProfileOutcome
    data object InvalidAvatarBackgroundColor : UpdateProfileOutcome
    data object NotSignedIn : UpdateProfileOutcome
    data class NetworkError(val cause: Throwable) : UpdateProfileOutcome
    data class Unknown(val cause: Throwable) : UpdateProfileOutcome
}

/**
 * An emoji pack the user can pick avatars from. The server returns the
 * full registry (starter + every premium pack); the picker filters
 * against local inventory using [unlockProductId] so a freshly-bought
 * pack appears immediately on the optimistic local row, without
 * waiting on a server-side inventory sync round-trip.
 *
 * The server is authoritative for what packs exist and what emojis
 * each contains; the client never invents a pack.
 */
data class AvatarPack(
    val id: String,
    val name: String,
    val emojis: List<String>,
    /**
     * Product id that unlocks this pack. `null` for the starter pack
     * (always available). Consumers should treat a pack as available
     * to the user iff `unlockProductId == null` OR the id is present
     * in the user's local inventory.
     */
    val unlockProductId: String? = null,
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
