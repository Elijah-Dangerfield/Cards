package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.WalletEventsTable
import com.dangerfield.cards.server.db.WalletsTable
import com.dangerfield.cards.server.db.toKotlinInstant
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.ApplyOutcome
import com.dangerfield.cards.server.domain.FindOrCreateResult
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.Wallet
import com.dangerfield.cards.server.domain.WalletEvent
import com.dangerfield.cards.server.domain.WalletRepository
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Exposed-backed implementation of [WalletRepository].
 *
 * The balance-mutating logic lives in [WalletLedger] (the single chip-
 * movement code path); this class just wraps it in a coroutine-friendly
 * `database.transaction { … }` so the wallet row + ledger row commit
 * together. Postgres's REPEATABLE READ isolation means a concurrent
 * writer can't slip between the read and the update — a serialization
 * failure surfaces to the caller, who retries.
 *
 * Idempotency: the ledger's `(user_id, idempotency_key)` primary key is
 * the dedup boundary — see [WalletLedger.applyInCurrentTransaction].
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
@OptIn(ExperimentalTime::class)
class PostgresWalletRepository(
    private val database: Database,
    private val clock: Clock,
) : WalletRepository {

    override suspend fun findOrCreateResult(userId: UserId): FindOrCreateResult = database.transaction {
        val existing = WalletLedger.readWallet(userId)
        if (existing != null) {
            FindOrCreateResult(wallet = existing, created = false)
        } else {
            // Note: if a concurrent writer wins the insert race,
            // createWithStarter re-reads their row but we still report
            // created = true here. Both callers are brand-new in the same
            // instant, so over-reporting created to the loser is harmless —
            // the worst case is two reveal attempts for one fresh account.
            FindOrCreateResult(wallet = WalletLedger.createWithStarter(userId, clock.now()), created = true)
        }
    }

    override suspend fun find(userId: UserId): Wallet? = database.transaction {
        WalletLedger.readWallet(userId)
    }

    override suspend fun apply(
        userId: UserId,
        idempotencyKey: String,
        delta: Long,
        reason: String,
    ): ApplyOutcome = database.transaction {
        WalletLedger.applyInCurrentTransaction(userId, idempotencyKey, delta, reason, clock.now())
    }

    override suspend fun recentEvents(userId: UserId, limit: Int): List<WalletEvent> =
        database.transaction {
            WalletEventsTable
                .selectAll()
                .where { WalletEventsTable.userId eq userId.value }
                .orderBy(WalletEventsTable.appliedAt to SortOrder.DESC)
                .limit(limit)
                .map { it.toDomain() }
        }

    override suspend fun hasIapSpend(userId: UserId): Boolean = database.transaction {
        WalletEventsTable
            .selectAll()
            .where {
                (WalletEventsTable.userId eq userId.value) and
                    (WalletEventsTable.reason like "iap.%")
            }
            .limit(1)
            .any()
    }

    override suspend fun deleteAllForUser(userId: UserId) {
        database.transaction {
            WalletEventsTable.deleteWhere { WalletEventsTable.userId eq userId.value }
            WalletsTable.deleteWhere { WalletsTable.userId eq userId.value }
        }
    }

    private fun ResultRow.toDomain(): WalletEvent = WalletEvent(
        userId = UserId(this[WalletEventsTable.userId]),
        idempotencyKey = this[WalletEventsTable.idempotencyKey],
        delta = this[WalletEventsTable.delta],
        reason = this[WalletEventsTable.reason],
        appliedAt = this[WalletEventsTable.appliedAt].toKotlinInstant(),
    )
}
