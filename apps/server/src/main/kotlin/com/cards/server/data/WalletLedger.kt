package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.WalletEventsTable
import com.dangerfield.cards.server.db.WalletsTable
import com.dangerfield.cards.server.db.toJavaInstant
import com.dangerfield.cards.server.db.toKotlinInstant
import com.dangerfield.cards.server.domain.ApplyOutcome
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.Wallet
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The chip-ledger primitive, expressed against the **current** Exposed
 * transaction — every chip movement in the system funnels through
 * [applyInCurrentTransaction], so there is exactly one place balances
 * change.
 *
 * [PostgresWalletRepository] wraps these in `database.transaction { }`
 * for the wallet API. The multiplayer buy-in composes
 * [applyInCurrentTransaction] with its own `table_sessions` insert inside
 * a single `database.transaction`, so the seat-claim and the debit commit
 * atomically (or roll back together) — closing the window where a crash
 * could otherwise leave a funded table row with no matching debit, which
 * cash-out would later credit as duplicated chips.
 *
 * Callers MUST already be inside a transaction. Semantics mirror the
 * V6 ledger: lazy-create the wallet (with the starter grant) on first
 * touch, dedupe on `(user_id, idempotency_key)`, and reject any delta
 * that would drive the balance below zero without writing a row.
 */
@OptIn(ExperimentalTime::class)
internal object WalletLedger {

    /** Read the wallet row, or null if the user has none yet. */
    fun readWallet(userId: UserId): Wallet? = WalletsTable
        .selectAll()
        .where { WalletsTable.userId eq userId.value }
        .singleOrNull()
        ?.toWallet()

    /**
     * Insert the wallet row with [Wallet.STARTER_GRANT]. If a concurrent
     * writer wins the race, their row stands and we re-read it.
     */
    fun createWithStarter(userId: UserId, now: Instant): Wallet {
        val javaNow = now.toJavaInstant()
        try {
            WalletsTable.insert {
                it[WalletsTable.userId] = userId.value
                it[WalletsTable.balance] = Wallet.STARTER_GRANT
                it[WalletsTable.createdAt] = javaNow
                it[WalletsTable.updatedAt] = javaNow
            }
        } catch (e: ExposedSQLException) {
            if (!e.isUniqueViolation()) throw e
        }
        return readWallet(userId) ?: error("Wallet row missing for user ${userId.value} after lazy-create")
    }

    /**
     * Apply a chip [delta] idempotently within the current transaction.
     * Re-applying the same [idempotencyKey] is a no-op that returns the
     * current balance with `wasAlreadyApplied = true`.
     */
    fun applyInCurrentTransaction(
        userId: UserId,
        idempotencyKey: String,
        delta: Long,
        reason: String,
        now: Instant,
    ): ApplyOutcome {
        val wallet = readWallet(userId) ?: createWithStarter(userId, now)

        if (eventExists(userId, idempotencyKey)) {
            return ApplyOutcome.Applied(balance = wallet.balance, wasAlreadyApplied = true)
        }

        val newBalance = wallet.balance + delta
        if (newBalance < 0) {
            return ApplyOutcome.InsufficientChips(balance = wallet.balance)
        }

        val javaNow = now.toJavaInstant()
        try {
            WalletEventsTable.insert {
                it[WalletEventsTable.userId] = userId.value
                it[WalletEventsTable.idempotencyKey] = idempotencyKey
                it[WalletEventsTable.delta] = delta
                it[WalletEventsTable.reason] = reason
                it[WalletEventsTable.appliedAt] = javaNow
            }
        } catch (e: ExposedSQLException) {
            // A concurrent writer beat us to this idempotency key — treat as
            // a replay and return the current balance (the > 0 guard above
            // means the CHECK(balance>=0) can't be what tripped).
            if (e.isUniqueViolation()) {
                return ApplyOutcome.Applied(balance = wallet.balance, wasAlreadyApplied = true)
            }
            throw e
        }

        WalletsTable.update({ WalletsTable.userId eq userId.value }) {
            it[WalletsTable.balance] = newBalance
            it[WalletsTable.updatedAt] = javaNow
        }

        return ApplyOutcome.Applied(balance = newBalance, wasAlreadyApplied = false)
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

    private fun ExposedSQLException.isUniqueViolation(): Boolean {
        val sqlState = (cause as? java.sql.SQLException)?.sqlState
            ?: (this as? java.sql.SQLException)?.sqlState
        return sqlState == POSTGRES_UNIQUE_VIOLATION_SQLSTATE
    }

    private const val POSTGRES_UNIQUE_VIOLATION_SQLSTATE = "23505"
}
