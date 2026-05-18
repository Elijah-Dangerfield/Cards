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
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

/**
 * Both fields are optional. Sending `{}` is a valid no-op; sending one
 * field updates only that field; sending both updates both.
 *
 * Validation lives at the route layer (length checks, emoji-in-pack
 * check). The DB constraint provides the last line of defense on
 * `displayName` uniqueness.
 */
@Serializable
data class PatchMeRequest(
    val displayName: String? = null,
    val avatarEmoji: String? = null,
)

@OptIn(ExperimentalTime::class)
internal fun Profile.toMeDto(): MeResponse = MeResponse(
    userId = userId.value.toString(),
    displayName = displayName,
    avatarEmoji = avatarEmoji,
    createdAtEpochMs = createdAt.toEpochMilliseconds(),
    updatedAtEpochMs = updatedAt.toEpochMilliseconds(),
)
