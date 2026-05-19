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
    /** Hex color from the server's palette; null = use theme default. */
    val avatarBackgroundColor: String? = null,
    /**
     * Mirrors the server's response, which itself mirrors the Supabase
     * JWT's `is_anonymous` claim. Authoritative — don't derive this from
     * the call site that triggered the fetch; always read it from here.
     */
    val isAnonymous: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)
