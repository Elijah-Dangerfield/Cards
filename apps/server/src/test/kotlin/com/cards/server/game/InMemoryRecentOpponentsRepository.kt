package com.dangerfield.cards.server.game

import com.dangerfield.cards.server.domain.HandTogether
import com.dangerfield.cards.server.domain.RecentOpponentsRepository
import com.dangerfield.cards.server.domain.Rivalry
import com.dangerfield.cards.server.domain.UserId
import java.util.UUID

/**
 * In-memory [RecentOpponentsRepository] test double. Directed `(viewer,
 * opponent)` keys dedup exactly like the Postgres PK; insertion order stands in
 * for recency so tests can assert the shelf without a clock or a database.
 *
 * The head-to-head counters accumulate the same way the real one does, so a
 * registry-level test can assert the scoreline a hand produced without standing
 * up Postgres.
 */
internal class InMemoryRecentOpponentsRepository : RecentOpponentsRepository {

    private data class Record(
        var handsTogether: Int = 0,
        var handsWon: Int = 0,
        var opponentHandsWon: Int = 0,
        var biggestPotWon: Long = 0,
        var sessionsTogether: Int = 0,
        var lastSessionId: UUID? = null,
    )

    private val rows = LinkedHashMap<Pair<UUID, UUID>, Record>()

    override suspend fun recordPlayedTogether(userId: UserId, opponentId: UserId, hand: HandTogether?) {
        if (userId == opponentId) return
        val key = userId.value to opponentId.value
        // Re-record bumps recency: drop then re-add so it lands at the end.
        val record = rows.remove(key) ?: Record()
        rows[key] = record
        if (hand == null) return
        record.handsTogether++
        if (hand.viewerWon) {
            record.handsWon++
            record.biggestPotWon = maxOf(record.biggestPotWon, hand.potTotal)
        }
        if (hand.opponentWon) record.opponentHandsWon++
        if (record.lastSessionId != hand.sessionId) {
            record.sessionsTogether++
            record.lastSessionId = hand.sessionId
        }
    }

    override suspend fun listRivalries(
        userId: UserId,
        limit: Int,
        minSessionsTogether: Int,
    ): List<Rivalry> = rows.entries
        .filter { it.key.first == userId.value && it.value.sessionsTogether >= minSessionsTogether }
        .reversed()
        .take(limit)
        .map { (key, record) ->
            Rivalry(
                opponentId = UserId(key.second),
                handsTogether = record.handsTogether,
                handsWon = record.handsWon,
                opponentHandsWon = record.opponentHandsWon,
                biggestPotWon = record.biggestPotWon,
                sessionsTogether = record.sessionsTogether,
                lastPlayedAtEpochMs = 0L,
            )
        }

    override suspend fun listRecent(userId: UserId, limit: Int): List<UserId> =
        rows.keys
            .filter { it.first == userId.value }
            .reversed()
            .take(limit)
            .map { UserId(it.second) }

    override suspend fun countDistinctOpponents(userId: UserId): Long =
        rows.keys.count { it.first == userId.value }.toLong()

    override suspend fun hasPlayedWith(userId: UserId, opponentId: UserId): Boolean =
        rows.containsKey(userId.value to opponentId.value)

    override suspend fun deleteAllForUser(userId: UserId) {
        rows.keys.removeAll { it.first == userId.value || it.second == userId.value }
    }
}
