package com.dangerfield.cards.server.domain

/**
 * Server-of-record for "the humans a user has shared a multiplayer hand with".
 * Backs the Home recently-played-with shelf and the friend-request gate (the
 * product rule that you may only friend someone this surfaced).
 *
 * Directed model: one row per (viewer, opponent). The authoritative hand loop
 * records *both* directions when a hand finishes, so [listRecent] for a user is
 * a plain recency scan and [hasPlayedWith] is symmetric in practice. Only humans
 * are ever recorded — the writer sources the pair set from the hand's per-human
 * outcome map, which excludes bots. See `V62__recently_played_with.sql` +
 * [com.dangerfield.cards.server.db.RecentlyPlayedWithTable].
 */
interface RecentOpponentsRepository {

    /**
     * Mark that [userId] and [opponentId] just shared a finished hand, bumping
     * the pair's recency. Idempotent on the `(user_id, opponent_id)` key — a
     * replayed hand-completion overwrites the timestamp rather than stacking
     * rows. Records one direction; the caller records the reverse too.
     */
    suspend fun recordPlayedTogether(userId: UserId, opponentId: UserId)

    /** The opponents [userId] has most-recently played with, newest-first, capped at [limit]. */
    suspend fun listRecent(userId: UserId, limit: Int): List<UserId>

    /**
     * The lifetime count of distinct humans [userId] has shared a finished hand
     * with. Drives the "players played with" lifetime stat. One row per
     * (viewer, opponent) pair, so this is the directed row count for the
     * viewer — bots never appear (the writer sources humans only).
     */
    suspend fun countDistinctOpponents(userId: UserId): Long

    /** Whether [userId] has ever shared a finished hand with [opponentId]. */
    suspend fun hasPlayedWith(userId: UserId, opponentId: UserId): Boolean

    /**
     * Wipe every row [userId] is a member of, from either column. Called from
     * the `DELETE /v1/me` cascade so account-delete doesn't strand the
     * opposite-direction row.
     */
    suspend fun deleteAllForUser(userId: UserId)
}

/**
 * No-op stand-in mirroring [com.dangerfield.cards.server.domain.NoOpHandsFinishedRepository]
 * — the default for [com.dangerfield.cards.server.game.DefaultGameSessionRegistry]
 * when no recorder is wanted (tests / unit code). Production never sees it: the
 * DI graph always resolves the real [RecentOpponentsRepository] binding into the
 * registry's constructor.
 */
object NoOpRecentOpponentsRepository : RecentOpponentsRepository {
    override suspend fun recordPlayedTogether(userId: UserId, opponentId: UserId) = Unit
    override suspend fun listRecent(userId: UserId, limit: Int): List<UserId> = emptyList()
    override suspend fun countDistinctOpponents(userId: UserId): Long = 0L
    override suspend fun hasPlayedWith(userId: UserId, opponentId: UserId): Boolean = false
    override suspend fun deleteAllForUser(userId: UserId) = Unit
}
