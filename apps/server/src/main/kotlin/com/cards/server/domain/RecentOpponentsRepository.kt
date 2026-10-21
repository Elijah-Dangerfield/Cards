package com.dangerfield.cards.server.domain

import java.util.UUID

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
     * the pair's recency and their head-to-head record. Records one direction;
     * the caller records the reverse too.
     *
     * The recency bump is idempotent on the `(user_id, opponent_id)` key, but
     * the counters are not: they are increments, and the same hand counted twice
     * is a wrong scoreline. [HandTogether.sessionId] plus [HandTogether.handNumber]
     * identify the hand, and the caller's dedup boundary (the hands-finished
     * ledger's idempotency key) is what guarantees one call per hand.
     *
     * Passing null [hand] records recency only, which is what a caller with no
     * outcome to hand (a backfill, a test fixture) wants.
     */
    suspend fun recordPlayedTogether(userId: UserId, opponentId: UserId, hand: HandTogether? = null)

    /**
     * The viewer's head-to-head records, most-recently-played first, capped at
     * [limit]. Only pairs with at least [minSessionsTogether] shared tables: one
     * table together is a game, not a rivalry, and a card that appears after a
     * single chance meeting reads as the app over-reading a coincidence.
     */
    suspend fun listRivalries(
        userId: UserId,
        limit: Int,
        minSessionsTogether: Int = 2,
    ): List<Rivalry>

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
    override suspend fun recordPlayedTogether(userId: UserId, opponentId: UserId, hand: HandTogether?) = Unit
    override suspend fun listRivalries(userId: UserId, limit: Int, minSessionsTogether: Int): List<Rivalry> =
        emptyList()
    override suspend fun listRecent(userId: UserId, limit: Int): List<UserId> = emptyList()
    override suspend fun countDistinctOpponents(userId: UserId): Long = 0L
    override suspend fun hasPlayedWith(userId: UserId, opponentId: UserId): Boolean = false
    override suspend fun deleteAllForUser(userId: UserId) = Unit
}

/**
 * One finished hand, as the head-to-head record needs to see it.
 *
 * [viewerWon] / [opponentWon] both come from the hand's own outcome rather than
 * being inferred from each other: a chop can leave neither of them a winner,
 * and a multiway pot can leave both up on the hand.
 */
data class HandTogether(
    val sessionId: UUID,
    val handNumber: Int,
    val viewerWon: Boolean,
    val opponentWon: Boolean,
    /** Total chips contested this hand. Same for every participant. */
    val potTotal: Long,
)

/**
 * The viewer's running record against one opponent. Directed: [handsWon] is
 * always the viewer's and [opponentHandsWon] always theirs, so a card renders
 * from this alone without knowing which way round the row was stored.
 */
data class Rivalry(
    val opponentId: UserId,
    val handsTogether: Int,
    val handsWon: Int,
    val opponentHandsWon: Int,
    val biggestPotWon: Long,
    val sessionsTogether: Int,
    val lastPlayedAtEpochMs: Long,
)
