package com.dangerfield.cards.server.game

import com.dangerfield.cards.server.domain.HandsFinishedRepository
import com.dangerfield.cards.server.domain.UserId
import java.util.UUID

/**
 * In-memory [HandsFinishedRepository] test double. The `(userId,
 * idempotencyKey)` key dedups exactly like the Postgres PK, so tests can
 * assert counting, the cumulative outcome tallies, and replay-idempotency
 * without a database.
 */
internal class InMemoryHandsFinishedRepository : HandsFinishedRepository {
    private data class Row(val bustsDealt: Int, val wonByFold: Boolean)

    private val rows = mutableMapOf<Pair<UUID, String>, Row>()

    override suspend fun recordHandFinished(
        userId: UserId,
        idempotencyKey: String,
        handSessionId: UUID,
        handNumber: Int,
        bustsDealt: Int,
        wonByFold: Boolean,
    ) {
        rows.putIfAbsent(userId.value to idempotencyKey, Row(bustsDealt, wonByFold))
    }

    override suspend fun countForUser(userId: UserId): Long =
        rows.keys.count { it.first == userId.value }.toLong()

    override suspend fun bustsDealtForUser(userId: UserId): Long =
        rows.entries.filter { it.key.first == userId.value }.sumOf { it.value.bustsDealt.toLong() }

    override suspend fun winsByFoldForUser(userId: UserId): Long =
        rows.entries.count { it.key.first == userId.value && it.value.wonByFold }.toLong()

    override suspend fun deleteAllForUser(userId: UserId) {
        rows.keys.removeAll { it.first == userId.value }
    }
}
