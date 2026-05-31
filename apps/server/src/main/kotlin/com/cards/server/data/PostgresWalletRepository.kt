package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.WalletEventsTable
import com.dangerfield.cards.server.db.WalletsTable
import com.dangerfield.cards.server.db.toJavaInstant
import com.dangerfield.cards.server.db.toKotlinInstant
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.ApplyOutcome
import com.dangerfield.cards.server.domain.FindOrCreateResult
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.Wallet
import com.dangerfield.cards.server.domain.WalletEvent
import com.dangerfield.cards.server.domain.WalletRepository
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Exposed-backed implementation of [WalletRepository].
 *
 * Concurrency model: every multi-statement operation (lazy-create,
 * apply) runs inside a single `database.transaction { … }` block so the
 * wallet row + ledger row commit together. Postgres's REPEATABLE READ
 * isolation (Hikari default in this project) means a concurrent
 * writer can't slip between the wallet read and the wallet update —
 * we'd see a serialization failure and the transaction would surface
 * the error to the caller, who retries.
 *
 * Idempotency: the ledger's `(user_id, idempotency_key)` primary key
 * is the dedup boundary. If a duplicate insert fires it raises a
 * unique-violation; we catch + treat as a no-op replay (no wallet
 * mutation, return the current balance).
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
        val existing = readWallet(userId)
        if (existing != null) {
            FindOrCreateResult(wallet = existing, created = false)
        } else {
            // Note: if a concurrent writer wins the insert race,
            // createWithStarter re-reads their row but we still report
            // created = true here. Both callers are brand-new in the same
            // instant, so over-reporting created to the loser is harmless —
            // the worst case is two reveal attempts for one fresh account.
            FindOrCreateResult(wallet = createWithStarter(userId, clock.now()), created = true)
        }
    }

    override suspend fun find(userId: UserId): Wallet? = database.transaction {
        readWallet(userId)
    }

    override suspend fun apply(
        userId: UserId,
        idempotencyKey: String,
        delta: Long,
        reason: String,
    ): ApplyOutcome = database.transaction {
        // Lazy-create the wallet on first apply too — a fresh client
        // may grant chips (e.g. starter, IAP) before it ever fetches
        // GET /v1/me/wallet.
        val wallet = readWallet(userId) ?: createWithStarter(userId, clock.now())

        // Replay detection: if an event with this key already exists for
        // this user, the previous apply already committed. Return the
        // current balance and flag as already-applied.
        if (eventExists(userId, idempotencyKey)) {
            return@transaction ApplyOutcome.Applied(
                balance = wallet.balance,
                wasAlreadyApplied = true,
            )
        }

        val newBalance = wallet.balance + delta
        if (newBalance < 0) {
            return@transaction ApplyOutcome.InsufficientChips(balance = wallet.balance)
        }

        val now = clock.now()
        try {
            WalletEventsTable.insert {
                it[WalletEventsTable.userId] = userId.value
                it[WalletEventsTable.idempotencyKey] = idempotencyKey
                it[WalletEventsTable.delta] = delta
                it[WalletEventsTable.reason] = reason
                it[WalletEventsTable.appliedAt] = now.toJavaInstant()
            }
        } catch (e: ExposedSQLException) {
            // A concurrent writer beat us to this idempotency key —
            // treat as a replay and return the current wallet balance.
            // The CHECK(balance>=0) violation can't happen here because
            // we already guarded above.
            if (e.isUniqueViolation()) {
                return@transaction ApplyOutcome.Applied(
                    balance = wallet.balance,
                    wasAlreadyApplied = true,
                )
            }
            throw e
        }

        WalletsTable.update({ WalletsTable.userId eq userId.value }) {
            it[WalletsTable.balance] = newBalance
            it[WalletsTable.updatedAt] = now.toJavaInstant()
        }

        ApplyOutcome.Applied(balance = newBalance, wasAlreadyApplied = false)
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

    override suspend fun deleteAllForUser(userId: UserId) {
        database.transaction {
            WalletEventsTable.deleteWhere { WalletEventsTable.userId eq userId.value }
            WalletsTable.deleteWhere { WalletsTable.userId eq userId.value }
        }
    }

    private fun readWallet(userId: UserId): Wallet? = WalletsTable
        .selectAll()
        .where { WalletsTable.userId eq userId.value }
        .singleOrNull()
        ?.toWallet()

    private fun createWithStarter(userId: UserId, now: kotlin.time.Instant): Wallet {
        val javaNow = now.toJavaInstant()
        try {
            WalletsTable.insert {
                it[WalletsTable.userId] = userId.value
                it[WalletsTable.balance] = Wallet.STARTER_GRANT
                it[WalletsTable.createdAt] = javaNow
                it[WalletsTable.updatedAt] = javaNow
            }
        } catch (e: ExposedSQLException) {
            // Concurrent writer raced us to the lazy-create. Their row
            // wins; re-read and return what's there.
            if (!e.isUniqueViolation()) throw e
        }
        return readWallet(userId) ?: error(
            "Wallet row missing for user ${userId.value} after lazy-create",
        )
    }

    private fun eventExists(userId: UserId, key: String): Boolean = WalletEventsTable
        .selectAll()
        .where {
            (WalletEventsTable.userId eq userId.value) and
                (WalletEventsTable.idempotencyKey eq key)
        }
        .limit(1)
        .any()

    private fun ResultRow.toWallet(): Wallet = Wallet(
        userId = UserId(this[WalletsTable.userId]),
        balance = this[WalletsTable.balance],
        createdAt = this[WalletsTable.createdAt].toKotlinInstant(),
        updatedAt = this[WalletsTable.updatedAt].toKotlinInstant(),
    )

    private fun ResultRow.toDomain(): WalletEvent = WalletEvent(
        userId = UserId(this[WalletEventsTable.userId]),
        idempotencyKey = this[WalletEventsTable.idempotencyKey],
        delta = this[WalletEventsTable.delta],
        reason = this[WalletEventsTable.reason],
        appliedAt = this[WalletEventsTable.appliedAt].toKotlinInstant(),
    )

    private fun ExposedSQLException.isUniqueViolation(): Boolean {
        val sqlState = (cause as? java.sql.SQLException)?.sqlState
            ?: (this as? java.sql.SQLException)?.sqlState
        return sqlState == POSTGRES_UNIQUE_VIOLATION_SQLSTATE
    }

    companion object {
        private const val POSTGRES_UNIQUE_VIOLATION_SQLSTATE = "23505"
    }
}
