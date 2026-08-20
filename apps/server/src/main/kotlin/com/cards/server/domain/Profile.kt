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
 * `userId` is a hard FK to Supabase's `auth.users(id)` with `ON DELETE
 * CASCADE`, added in `V11__fk_auth_users.sql` (Testcontainers gets a minimal
 * `auth.users` stub from `init-auth.sql`). Deleting the auth user takes the
 * profile — and every other per-user row — with it. A request whose JWT names
 * an id that isn't there can't create a profile; the repository detects that
 * before writing and raises [UnknownAuthUserException].
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
)
