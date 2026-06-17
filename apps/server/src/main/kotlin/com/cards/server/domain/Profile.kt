package com.dangerfield.cards.server.domain

import java.util.UUID
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Identifier of an authenticated user.
 *
 * Wraps the UUID Supabase Auth puts in the `sub` claim of every JWT it
 * issues (the primary key of `auth.users`). Wrapped in a value class so
 * it's harder to accidentally pass some other UUID where a user id is
 * expected.
 */
@JvmInline
value class UserId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun parse(s: String): UserId = UserId(UUID.fromString(s))
    }
}

/**
 * Per-user profile row owned by our server.
 *
 * Joins to Supabase's `auth.users(id)` by `userId`. We do **not** enforce
 * the FK at the schema level — Supabase manages `auth.users` and we don't
 * want a hard FK to a table whose lifecycle we don't control (and that
 * doesn't exist in Testcontainers). The application layer enforces
 * "no profile without a valid JWT," which is stronger anyway.
 *
 * `displayName` is globally unique — we generate it via collision-checked
 * random combination and reference players by it in room/leaderboard UI.
 *
 * `avatarEmoji` holds a single emoji codepoint sequence. Future avatar
 * unlocks live alongside this; emoji stays as the V1 default.
 *
 * `avatarBackgroundColor` is a hex string from [AvatarPalette]. NULL =
 * "use the theme default" — the client renders surface-secondary in
 * that case.
 */
@OptIn(ExperimentalTime::class)
data class Profile(
    val userId: UserId,
    val displayName: String,
    val avatarEmoji: String,
    val avatarBackgroundColor: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /**
     * Achievement ids the owner features on their Player Card, in display
     * order (most-significant first). Empty = "never chosen" — the client
     * falls back to the most-recently-earned badges. Capped at
     * [MAX_FEATURED_BADGES] by the route layer.
     */
    val featuredBadgeIds: List<String> = emptyList(),
)

/** Most badges a Player Card features. Mirrored client-side. */
const val MAX_FEATURED_BADGES: Int = 3
