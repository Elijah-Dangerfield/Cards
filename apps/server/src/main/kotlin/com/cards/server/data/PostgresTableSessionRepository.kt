package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.TableSessionsTable
import com.dangerfield.cards.server.db.toJavaInstant
import com.dangerfield.cards.server.db.toKotlinInstant
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.TableSession
import com.dangerfield.cards.server.domain.TableSessionRepository
import com.dangerfield.cards.server.domain.TableSessionStatus
import com.dangerfield.cards.server.domain.UserId
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Exposed-backed [TableSessionRepository] — pure row persistence for the
 * `table_sessions` lifecycle. Each method runs in its own
 * `database.transaction`.
 *
 * The buy-in (row insert + wallet debit) is NOT here: it must commit
 * atomically with the debit, so it lives in the table-session service's
 * single transaction (via [WalletLedger]). This layer owns the reads, the
 * forward-only `markClosing`/`markClosed` flips, the rebuy counter, the
 * boot-sweep listing, and the account-delete cascade — all crash-safe in
 * concert with the keyed, idempotent wallet movements.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
@OptIn(ExperimentalTime::class)
class PostgresTableSessionRepository(
    private val database: Database,
    private val clock: Clock,
) : TableSessionRepository {

    override suspend fun find(sessionId: UUID): TableSession? = database.transaction {
        readById(sessionId)
    }

    override suspend fun findActiveForUser(userId: UserId): TableSession? = database.transaction {
        TableSessionsTable
            .selectAll()
            .where { (TableSessionsTable.userId eq userId.value) and (TableSessionsTable.status neq CLOSED) }
            .singleOrNull()
            ?.toDomain()
    }

    override suspend fun findActiveByRoom(roomCode: String): List<TableSession> = database.transaction {
        TableSessionsTable
            .selectAll()
            .where { (TableSessionsTable.roomCode eq roomCode) and (TableSessionsTable.status neq CLOSED) }
            .map { it.toDomain() }
    }

    override suspend fun markClosing(sessionId: UUID): Boolean = database.transaction {
        val updated = TableSessionsTable.update({
            (TableSessionsTable.sessionId eq sessionId) and (TableSessionsTable.status eq OPEN)
        }) {
            it[TableSessionsTable.status] = CLOSING
        }
        updated > 0
    }

    override suspend fun markClosed(sessionId: UUID): Boolean = database.transaction {
        val updated = TableSessionsTable.update({
            (TableSessionsTable.sessionId eq sessionId) and (TableSessionsTable.status eq CLOSING)
        }) {
            it[TableSessionsTable.status] = CLOSED
            it[TableSessionsTable.closedAt] = clock.now().toJavaInstant()
        }
        updated > 0
    }

    override suspend fun incrementRebuy(sessionId: UUID): Int = database.transaction {
        // Rebuys for a given session are serialized by the game session's
        // mutex upstream, so a plain read-then-write is race-free here.
        val current = TableSessionsTable
            .selectAll()
            .where { TableSessionsTable.sessionId eq sessionId }
            .single()[TableSessionsTable.rebuyCount]
        val next = current + 1
        TableSessionsTable.update({ TableSessionsTable.sessionId eq sessionId }) {
            it[TableSessionsTable.rebuyCount] = next
        }
        next
    }

    override suspend fun listActive(): List<TableSession> = database.transaction {
        TableSessionsTable
            .selectAll()
            .where { TableSessionsTable.status neq CLOSED }
            .map { it.toDomain() }
    }

    override suspend fun recordSubsidyGranted(sessionId: UUID, amount: Long) {
        database.transaction {
            TableSessionsTable.update({ TableSessionsTable.sessionId eq sessionId }) {
                it[TableSessionsTable.subsidyGranted] = amount
            }
        }
    }

    override suspend fun subsidyGrantedSince(userId: UserId, since: Instant): Long = database.transaction {
        TableSessionsTable
            .selectAll()
            .where {
                (TableSessionsTable.userId eq userId.value) and
                    (TableSessionsTable.closedAt greaterEq since.toJavaInstant())
            }
            .sumOf { it[TableSessionsTable.subsidyGranted] }
    }

    override suspend fun deleteAllForUser(userId: UserId) {
        database.transaction {
            TableSessionsTable.deleteWhere { TableSessionsTable.userId eq userId.value }
        }
    }

    private fun readById(sessionId: UUID): TableSession? = TableSessionsTable
        .selectAll()
        .where { TableSessionsTable.sessionId eq sessionId }
        .singleOrNull()
        ?.toDomain()

    private fun ResultRow.toDomain(): TableSession = TableSession(
        sessionId = this[TableSessionsTable.sessionId],
        userId = UserId(this[TableSessionsTable.userId]),
        roomCode = this[TableSessionsTable.roomCode],
        stakeTier = this[TableSessionsTable.stakeTier],
        buyIn = this[TableSessionsTable.buyIn],
        rebuyCount = this[TableSessionsTable.rebuyCount],
        status = TableSessionStatus.fromDb(this[TableSessionsTable.status]),
        openedAt = this[TableSessionsTable.openedAt].toKotlinInstant(),
        closedAt = this[TableSessionsTable.closedAt]?.toKotlinInstant(),
        subsidized = this[TableSessionsTable.subsidized],
        subsidyGranted = this[TableSessionsTable.subsidyGranted],
    )

    private companion object {
        val OPEN = TableSessionStatus.Open.dbValue
        val CLOSING = TableSessionStatus.Closing.dbValue
        val CLOSED = TableSessionStatus.Closed.dbValue
    }
}
