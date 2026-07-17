package com.dangerfield.cards.libraries.identity.impl

import kotlinx.serialization.Serializable

/**
 * Wire type mirroring the server's `GET /v1/me` response. Internal to
 * `:libraries:identity:impl` — feature code consumes the domain
 * [com.dangerfield.cards.libraries.identity.profile.Profile.Authenticated]
 * type instead.
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
    /**
     * True only on the response that lazy-created this profile row — i.e. this
     * `GET /v1/me` is a brand-new account's first contact. Authoritative,
     * deterministic net-new signal for the auth-outcome classifier (SIGN-UP vs
     * SIGN-IN), replacing the best-effort `walletJustCreated` proxy. One-shot:
     * only the first hydrate after account creation carries true, so callers
     * capture it at the post-auth hydrate, not on every read. Defaults false so
     * a server on an older build degrades to "returning".
     */
    val isNewAccount: Boolean = false,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)
