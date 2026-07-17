package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.Profile
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime

/**
 * Wire format for `GET /v1/me` and `PATCH /v1/me`.
 *
 * Kept separate from the domain type so the JSON shape can evolve
 * without touching the repository. Timestamps are Long epoch ms.
 */
@Serializable
data class MeResponse(
    val userId: String,
    val displayName: String,
    val avatarEmoji: String,
    /** Hex color from the palette, or null = use theme default. */
    val avatarBackgroundColor: String? = null,
    /**
     * Mirrors Supabase's `is_anonymous` JWT claim. Authoritative for "should
     * we show the claim-your-account prompt." Flips to false on its own once
     * the client calls `linkIdentity(provider)` and the next JWT refresh
     * lands; the server doesn't need to persist this — it reads the live
     * claim on every request.
     */
    val isAnonymous: Boolean,
    /**
     * True only on the response that lazy-created this profile row — i.e. this
     * is a brand-new account's first contact. The client's auth-outcome
     * classifier reads it to tell SIGN-UP (net-new) from SIGN-IN (existing):
     * authoritative and deterministic, unlike the old `walletCreated` proxy
     * that depended on a best-effort wallet sync. Defaults false so older
     * responses/clients degrade to "returning". One-shot: only the first
     * `GET /v1/me` after account creation carries true.
     */
    val isNewAccount: Boolean = false,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

/**
 * Wire format for `GET /v1/me/stats` — lifetime stats the client can't
 * derive from its local progression counters because they live only on the
 * server. Kept off [MeResponse] so the profile DTO + its PATCH echo stay a
 * pure projection of the profile row.
 */
@Serializable
data class MeStatsResponse(
    /** Distinct humans the caller has shared a finished multiplayer hand with. */
    val distinctOpponentsPlayed: Long,
)

/**
 * All fields are optional. Sending `{}` is a valid no-op; sending one
 * field updates only that field.
 *
 * For `avatarBackgroundColor`, a JSON `null` is indistinguishable from
 * "not present" on the wire after the framework deserializes. To express
 * "clear this back to the default" the client sets `clearAvatarBackgroundColor`
 * to true. Annoying but unambiguous; the alternative (a tri-state nullable
 * wrapper) read worse for one field.
 *
 * Validation lives at the route layer (length checks, emoji-in-pack
 * check, palette membership). The DB constraint provides the last line
 * of defense on `displayName` uniqueness.
 */
@Serializable
data class PatchMeRequest(
    val displayName: String? = null,
    val avatarEmoji: String? = null,
    val avatarBackgroundColor: String? = null,
    val clearAvatarBackgroundColor: Boolean = false,
)

@OptIn(ExperimentalTime::class)
internal fun Profile.toMeDto(isAnonymous: Boolean, isNewAccount: Boolean = false): MeResponse = MeResponse(
    userId = userId.value.toString(),
    displayName = displayName,
    avatarEmoji = avatarEmoji,
    avatarBackgroundColor = avatarBackgroundColor,
    isAnonymous = isAnonymous,
    isNewAccount = isNewAccount,
    createdAtEpochMs = createdAt.toEpochMilliseconds(),
    updatedAtEpochMs = updatedAt.toEpochMilliseconds(),
)
