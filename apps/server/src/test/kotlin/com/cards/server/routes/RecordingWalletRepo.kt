package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.ApplyOutcome
import com.dangerfield.cards.server.domain.FindOrCreateResult
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.Wallet
import com.dangerfield.cards.server.domain.WalletEvent
import com.dangerfield.cards.server.domain.WalletRepository
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Minimal in-memory [WalletRepository] for route tests that only care about
 * which ledger applies happened — the reward-grant paths on the progression
 * and achievements sync routes. Dedupes on the idempotency key like the real
 * repo so replay tests can assert single-credit.
 */
@OptIn(ExperimentalTime::class)
class RecordingWalletRepo : WalletRepository {

    data class RecordedApply(val idempotencyKey: String, val delta: Long, val reason: String)

    val applies: MutableList<RecordedApply> = mutableListOf()
    private val appliedKeys: MutableSet<String> = mutableSetOf()
    private var balance: Long = Wallet.STARTER_GRANT

    override suspend fun findOrCreateResult(userId: UserId): FindOrCreateResult =
        FindOrCreateResult(
            wallet = Wallet(
                userId = userId,
                balance = balance,
                createdAt = Instant.fromEpochMilliseconds(0),
                updatedAt = Instant.fromEpochMilliseconds(0),
            ),
            created = false,
        )

    override suspend fun find(userId: UserId): Wallet? = findOrCreateResult(userId).wallet

    override suspend fun apply(
        userId: UserId,
        idempotencyKey: String,
        delta: Long,
        reason: String,
    ): ApplyOutcome {
        if (!appliedKeys.add(idempotencyKey)) {
            return ApplyOutcome.Applied(balance = balance, wasAlreadyApplied = true)
        }
        applies += RecordedApply(idempotencyKey, delta, reason)
        balance += delta
        return ApplyOutcome.Applied(balance = balance, wasAlreadyApplied = false)
    }

    override suspend fun recentEvents(userId: UserId, limit: Int): List<WalletEvent> = emptyList()
    override suspend fun hasIapSpend(userId: UserId): Boolean = false

    override suspend fun deleteAllForUser(userId: UserId) = Unit
}
