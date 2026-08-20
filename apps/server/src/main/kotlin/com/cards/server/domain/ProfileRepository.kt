package com.dangerfield.cards.server.domain

import com.dangerfield.cards.server.http.ClientContext

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
    /**
     * [signupPlatform] is recorded only on the call that actually inserts the
     * row, and is never revisited afterwards — it's the cohort dimension the
     * growth graph splits on, so a returning user on a second OS must not be
     * able to rewrite it (V90). Callers that aren't a real client request pass
     * the default and land as `other`.
     */
    suspend fun findOrCreate(
        userId: UserId,
        signupPlatform: ClientContext.Platform = ClientContext.Platform.Other,
    ): Profile

    /**
     * Like [findOrCreate] but reports whether THIS call created the profile row
     * — i.e. this is a brand-new account's first contact. It's the authoritative
     * "net-new account" signal (parity with the wallet's and progression's
     * `created` flags): deterministic and decoupled from the best-effort wallet
     * sync the client used to infer newness from. `GET /v1/me` surfaces it as
     * `MeResponse.isNewAccount` for the client's auth-outcome classifier
     * (SignedUp vs SignedIn). Default delegates with `created = false` for fakes
     * that don't distinguish; the Postgres impl reports the real flag.
     */
    suspend fun findOrCreateResult(
        userId: UserId,
        signupPlatform: ClientContext.Platform = ClientContext.Platform.Other,
    ): FindOrCreateProfileResult =
        FindOrCreateProfileResult(findOrCreate(userId, signupPlatform), created = false)

    suspend fun findById(userId: UserId): Profile?

    /**
     * Batch counterpart to [findById] — resolve many ids at once so a caller
     * like `GET /v1/profiles?ids=…` doesn't fire one DB round-trip per id.
     * Unknown ids are simply absent from the result and order is not
     * guaranteed, so callers preserving request order re-associate by
     * [Profile.userId]. The default is the naive per-id fallback (fine for
     * fakes and small callers); the Postgres impl overrides it with a single
     * `WHERE user_id IN (…)` query.
     */
    suspend fun findByIds(userIds: List<UserId>): List<Profile> =
        userIds.mapNotNull { findById(it) }

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

    /**
     * The account-upgrade lineage for [userId]: every user id that has owned
     * this install (the caller plus everyone sharing the caller's
     * `install_id`), resolved server-side from the caller's own profile row so
     * a client can't inject a lineage it isn't part of.
     *
     * Used by billing to accept a StoreKit receipt whose `appAccountToken` was
     * stamped under a prior identity on the same device (BILL-11). Always
     * includes [userId]; returns just `{userId}` when the profile is unknown or
     * its `install_id` is unset (no lineage to widen to), which preserves the
     * strict "token must equal the caller" binding.
     */
    suspend fun findInstallLineage(userId: UserId): Set<UserId> = setOf(userId)

    /**
     * Every profile's (userId, displayName), for the admin backfill that
     * mirrors names into Supabase auth metadata (`POST
     * /v1/admin/sync-display-names`). Default for fakes; the Postgres impl
     * overrides with a two-column projection. V1-scale: the whole set fits
     * in memory comfortably (same call as the anon-sweep's full listing).
     */
    suspend fun listAllDisplayNames(): List<ProfileDisplayName> = emptyList()
}

/** One row of [ProfileRepository.listAllDisplayNames]. */
data class ProfileDisplayName(val userId: UserId, val displayName: String)

/** Result of [ProfileRepository.findOrCreateResult]: the profile plus whether
 *  THIS call created it (brand-new account). Parity with the wallet's
 *  `FindOrCreateResult` and progression's `FindOrCreateProgressionResult`. */
data class FindOrCreateProfileResult(val profile: Profile, val created: Boolean)

sealed interface UpdateProfileOutcome {
    data class Success(val profile: Profile) : UpdateProfileOutcome
    /** Some other user already owns that display name. */
    data object DisplayNameTaken : UpdateProfileOutcome
    /** No profile exists for this user yet — caller should hit GET /v1/me first. */
    data object NotFound : UpdateProfileOutcome
}

