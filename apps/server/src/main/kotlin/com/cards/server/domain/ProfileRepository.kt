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
    /**
     * Patch a profile. `null` for a field = leave alone. Sentinel for
     * "clear back to default" is [ClearAvatarBackgroundColor] in the
     * `avatarBackgroundColor` slot — null in that slot means "don't touch",
     * the sentinel means "set the column to NULL." Keeps the route layer
     * honest about intent without using a wrapper type for one field.
     */
    suspend fun update(
        userId: UserId,
        displayName: String?,
        avatarEmoji: String?,
        avatarBackgroundColor: String? = null,
        clearAvatarBackgroundColor: Boolean = false,
    ): UpdateProfileOutcome

    /**
     * Remove the profile row for [userId]. Idempotent — succeeds whether
     * a row existed or not. The caller (`DELETE /v1/me`) pairs this with a
     * Supabase Admin API call that deletes the underlying `auth.users`
     * row; this method only owns OUR table.
     */
    suspend fun delete(userId: UserId)
}

sealed interface UpdateProfileOutcome {
    data class Success(val profile: Profile) : UpdateProfileOutcome
    /** Some other user already owns that display name. */
    data object DisplayNameTaken : UpdateProfileOutcome
    /** No profile exists for this user yet — caller should hit GET /v1/me first. */
    data object NotFound : UpdateProfileOutcome
}

