package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.HandFinishedEventsTable
import com.dangerfield.cards.server.db.toJavaInstant
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.HandsFinishedRepository
import com.dangerfield.cards.server.domain.UserId
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Exposed-backed [HandsFinishedRepository]. Append-only ledger; the
 * `(user_id, idempotency_key)` PK is the dedup boundary, so a replayed
 * hand-completion raises a unique-violation we swallow as a no-op. The
 * per-user count is `COUNT(*)` over the PK prefix, the cumulative outcome
 * tallies a `SUM(busts_dealt)` / `COUNT(won_by_fold)` over the same rows —
 * gating reads are infrequent (only at achievement-grant time), so no summary
 * row is kept.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
@OptIn(ExperimentalTime::class)
class PostgresHandsFinishedRepository(
    private val database: Database,
    private val clock: Clock,
) : HandsFinishedRepository {

    override suspend fun recordHandFinished(
        userId: UserId,
        idempotencyKey: String,
        handSessionId: UUID,
        handNumber: Int,
        bustsDealt: Int,
        wonByFold: Boolean,
    ) {
        database.transaction {
            try {
                HandFinishedEventsTable.insert {
                    it[HandFinishedEventsTable.userId] = userId.value
                    it[HandFinishedEventsTable.idempotencyKey] = idempotencyKey
                    it[HandFinishedEventsTable.handSessionId] = handSessionId
                    it[HandFinishedEventsTable.handNumber] = handNumber
                    it[HandFinishedEventsTable.finishedAt] = clock.now().toJavaInstant()
                    it[HandFinishedEventsTable.bustsDealt] = bustsDealt
                    it[HandFinishedEventsTable.wonByFold] = wonByFold
                }
            } catch (e: ExposedSQLException) {
                // A row for this key already committed — idempotent replay.
                if (!e.isUniqueViolation()) throw e
            }
        }
    }

    override suspend fun countForUser(userId: UserId): Long = database.transaction {
        HandFinishedEventsTable
            .selectAll()
            .where { HandFinishedEventsTable.userId eq userId.value }
            .count()
    }

    override suspend fun bustsDealtForUser(userId: UserId): Long = database.transaction {
        val total = HandFinishedEventsTable.bustsDealt.sum()
        HandFinishedEventsTable
            .select(total)
            .where { HandFinishedEventsTable.userId eq userId.value }
            .firstOrNull()
            ?.get(total)
            ?.toLong()
            ?: 0L
    }

    override suspend fun winsByFoldForUser(userId: UserId): Long = database.transaction {
        HandFinishedEventsTable
            .selectAll()
            .where {
                (HandFinishedEventsTable.userId eq userId.value) and
                    (HandFinishedEventsTable.wonByFold eq true)
            }
            .count()
    }

    override suspend fun deleteAllForUser(userId: UserId) {
        database.transaction {
            HandFinishedEventsTable.deleteWhere { HandFinishedEventsTable.userId eq userId.value }
        }
    }

    private fun ExposedSQLException.isUniqueViolation(): Boolean {
        val sqlState = (cause as? java.sql.SQLException)?.sqlState
            ?: (this as? java.sql.SQLException)?.sqlState
        return sqlState == POSTGRES_UNIQUE_VIOLATION_SQLSTATE
    }

    companion object {
        private const val POSTGRES_UNIQUE_VIOLATION_SQLSTATE = "23505"
    }
}
