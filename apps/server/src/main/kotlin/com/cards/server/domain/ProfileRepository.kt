package com.dangerfield.cards.server.domain

/**
 * The single high-level operation `/v1/me` needs.
 *
 * On the first call for a given user (Supabase issued them a JWT, we
 * verified it, this is the first time we've seen the user_id) the
 * repository generates a random display name + emoji and inserts a
 * profile row. On subsequent calls, it returns the existing row.
 *
 * Idempotent on `userId`. Two concurrent first-contact requests for the
 * same user race; one wins the insert, the other gets a unique-violation
 * on the `userId` primary key and falls back to the read path.
 *
 * Username collisions are handled at the `display_name` unique constraint
 * via retry — see the implementation.
 */
interface ProfileRepository {
    suspend fun findOrCreate(userId: UserId): Profile
    suspend fun findById(userId: UserId): Profile?

    /**
     * Apply a partial update. Either field may be omitted (null = leave
     * alone). The server enforces:
     *  - `displayName` uniqueness via the DB constraint (we map the
     *    violation to [UpdateProfileOutcome.DisplayNameTaken]).
     *  - `avatarEmoji` membership in the curated starter pack (the route
     *    layer validates before reaching here).
     */
    suspend fun update(
        userId: UserId,
        displayName: String?,
        avatarEmoji: String?,
    ): UpdateProfileOutcome
}

sealed interface UpdateProfileOutcome {
    data class Success(val profile: Profile) : UpdateProfileOutcome
    /** Some other user already owns that display name. */
    data object DisplayNameTaken : UpdateProfileOutcome
    /** No profile exists for this user yet — caller should hit GET /v1/me first. */
    data object NotFound : UpdateProfileOutcome
}

