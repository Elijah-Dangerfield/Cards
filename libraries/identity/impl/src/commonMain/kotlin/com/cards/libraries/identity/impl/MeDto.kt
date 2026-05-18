package com.dangerfield.cards.libraries.identity.impl

import kotlinx.serialization.Serializable

/**
 * Wire type mirroring the server's `GET /v1/me` response. Internal to
 * `:libraries:identity:impl` — feature code consumes the domain
 * [com.dangerfield.cards.libraries.identity.Identity] type instead.
 */
@Serializable
data class MeDto(
    val userId: String,
    val displayName: String,
    val avatarEmoji: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)
