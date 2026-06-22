package com.dangerfield.cards.server.game

import com.dangerfield.cards.server.domain.RecentOpponentsRepository
import com.dangerfield.cards.server.domain.UserId
import java.util.UUID

/**
 * In-memory [RecentOpponentsRepository] test double. Directed `(viewer,
 * opponent)` keys dedup exactly like the Postgres PK; insertion order stands in
 * for recency so tests can assert the shelf without a clock or a database.
 */
internal class InMemoryRecentOpponentsRepository : RecentOpponentsRepository {
    private val rows = LinkedHashMap<Pair<UUID, UUID>, Unit>()

    override suspend fun recordPlayedTogether(userId: UserId, opponentId: UserId) {
        if (userId == opponentId) return
        val key = userId.value to opponentId.value
        // Re-record bumps recency: drop then re-add so it lands at the end.
        rows.remove(key)
        rows[key] = Unit
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
