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
        /**
         * Replace the featured-badge list wholesale. `null` = leave alone;
         * an empty list = clear the selection back to the default. The route
         * layer caps + de-dups before this point.
         */
        featuredBadgeIds: List<String>? = null,
    ): UpdateProfileOutcome

    /**
     * Remove the profile row for [userId]. Idempotent — succeeds whether
     * a row existed or not. The caller (`DELETE /v1/me`) pairs this with a
     * Supabase Admin API call that deletes the underlying `auth.users`
     * row; this method only owns OUR table.
     */
    suspend fun delete(userId: UserId)

    /**
     * Tag the profile with the caller's [installId]. Compare-and-skip:
     * a write only fires if the stored value differs from [installId], so
     * the common case (same install hitting /v1/me repeatedly) is a single
     * SELECT.
     *
     * Returns the prior value that was overwritten (or `null` if the
     * column was unset or already matched). A non-null, non-equal prior
     * value is the L1 cleanup cue — the previous owner of this install
     * left an orphan row (see docs/recovery-and-orphaned-accounts.md).
     *
     * No-op when the profile doesn't exist (the route layer always calls
     * after `findOrCreate`, so this is defensive only).
     */
    suspend fun touchInstallId(userId: UserId, installId: java.util.UUID): java.util.UUID?

    /**
     * L1 orphan-cleanup pre-filter. Returns anonymous-user profile rows
     * that share [installId] with the caller (excluding [currentUserId])
     * AND have no IAP wallet events. The "user signed out then in to a
     * different anon account on the same install" siblings the L1 sweep
     * is responsible for reaping — see
     * [docs/recovery-and-orphaned-accounts.md](../../../../../../../../docs/recovery-and-orphaned-accounts.md).
     *
     * The cheap SQL gate filters by install_id + anon status + zero IAP
     * spend. Per-candidate verification (engagement-grade inventory,
     * active room seat) stays in Kotlin so the policy can evolve without
     * a migration; see [OrphanInstallSweep].
     */
    suspend fun findInstallSiblings(
        installId: java.util.UUID,
        currentUserId: UserId,
    ): List<UserId>
}

sealed interface UpdateProfileOutcome {
    data class Success(val profile: Profile) : UpdateProfileOutcome
    /** Some other user already owns that display name. */
    data object DisplayNameTaken : UpdateProfileOutcome
    /** No profile exists for this user yet — caller should hit GET /v1/me first. */
    data object NotFound : UpdateProfileOutcome
}

