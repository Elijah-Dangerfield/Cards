package com.dangerfield.cards.server.game

import com.dangerfield.cards.server.domain.HandsFinishedRepository
import com.dangerfield.cards.server.domain.UserId
import java.util.UUID

/**
 * In-memory [HandsFinishedRepository] test double. The `(userId,
 * idempotencyKey)` set dedups exactly like the Postgres PK, so tests can
 * assert both counting and replay-idempotency without a database.
 */
internal class InMemoryHandsFinishedRepository : HandsFinishedRepository {
    private val rows = mutableSetOf<Pair<UUID, String>>()

    override suspend fun recordHandFinished(
        userId: UserId,
        idempotencyKey: String,
        handSessionId: UUID,
        handNumber: Int,
    ) {
        rows += userId.value to idempotencyKey
    }

    override suspend fun countForUser(userId: UserId): Long =
        rows.count { it.first == userId.value }.toLong()

    override suspend fun deleteAllForUser(userId: UserId) {
        rows.removeAll { it.first == userId.value }
    }
}
