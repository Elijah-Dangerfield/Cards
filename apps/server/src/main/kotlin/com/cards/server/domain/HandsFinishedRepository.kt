package com.dangerfield.cards.server.domain

import java.util.UUID

/**
 * Server-witnessed count of hands a user has finished in the authoritative
 * (multiplayer) hand loop.
 *
 * Now that hand resolution runs server-side, the server can witness — rather
 * than trust the client for — how many hands a user has actually played to
 * completion. Multiplayer achievements (the `serverWitnessed` set in
 * [ClientGrantableAchievements]) gate on this count so a malicious client
 * can't self-grant an MP achievement it didn't earn.
 *
 * Append-only ledger keyed by `(userId, idempotencyKey)`; the key shape is
 * `<sessionId>:<handNumber>:<userId>`, so a hand-completion observed more than
 * once (e.g. a snapshot replay after a server restart) collapses to one row.
 * Mirrors the [ProgressionRepository] ledger pattern. See
 * `V56__hand_finished_counts.sql`.
 */
interface HandsFinishedRepository {

    /**
     * Record that [userId] finished a hand. Idempotent on [idempotencyKey] —
     * re-recording the same key is a no-op replay, so the count never double-
     * increments across retries / snapshot replays.
     */
    suspend fun recordHandFinished(
        userId: UserId,
        idempotencyKey: String,
        handSessionId: UUID,
        handNumber: Int,
    )

    /** Total hands [userId] has finished server-side. */
    suspend fun countForUser(userId: UserId): Long

    /**
     * Wipe the ledger for a user. Called from the `DELETE /v1/me` cascade so
     * account-delete doesn't leave orphan rows.
     */
    suspend fun deleteAllForUser(userId: UserId)
}

/**
 * No-op stand-in mirroring [com.dangerfield.cards.server.game.NoOpSessionSnapshotStore]
 * — the default for [com.dangerfield.cards.server.game.DefaultGameSessionRegistry]
 * when no counter is wanted (tests / unit code). Production never sees it: the
 * DI graph always resolves the real [HandsFinishedRepository] binding into the
 * registry's constructor, so the default is dead code on the wired path.
 */
object NoOpHandsFinishedRepository : HandsFinishedRepository {
    override suspend fun recordHandFinished(
        userId: UserId,
        idempotencyKey: String,
        handSessionId: UUID,
        handNumber: Int,
    ) = Unit

    override suspend fun countForUser(userId: UserId): Long = 0L

    override suspend fun deleteAllForUser(userId: UserId) = Unit
}
