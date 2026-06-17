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
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    /**
     * Achievement ids the owner features on their Player Card, in display
     * order. Empty = never chosen — the client renders most-recently-earned
     * badges by default.
     */
    val featuredBadgeIds: List<String> = emptyList(),
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
    /**
     * Replace the featured-badge selection wholesale. `null` (absent) =
     * leave alone; an empty list = clear back to the default. The route
     * caps the list at [com.dangerfield.cards.server.domain.MAX_FEATURED_BADGES]
     * and de-dups before persisting.
     */
    val featuredBadgeIds: List<String>? = null,
)

@OptIn(ExperimentalTime::class)
internal fun Profile.toMeDto(isAnonymous: Boolean): MeResponse = MeResponse(
    userId = userId.value.toString(),
    displayName = displayName,
    avatarEmoji = avatarEmoji,
    avatarBackgroundColor = avatarBackgroundColor,
    isAnonymous = isAnonymous,
    createdAtEpochMs = createdAt.toEpochMilliseconds(),
    updatedAtEpochMs = updatedAt.toEpochMilliseconds(),
    featuredBadgeIds = featuredBadgeIds,
)
