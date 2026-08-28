package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.UserProgressionTable
import com.dangerfield.cards.server.db.XpEventsTable
import com.dangerfield.cards.server.db.toJavaInstant
import com.dangerfield.cards.server.db.toKotlinInstant
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.ApplyXpBatchResult
import com.dangerfield.cards.server.domain.FindOrCreateProgressionResult
import com.dangerfield.cards.server.domain.ProgressionRepository
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.UserProgression
import com.dangerfield.cards.server.domain.XpEvent
import com.dangerfield.cards.server.domain.XpEventInput
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.BooleanColumnType
import org.jetbrains.exposed.sql.IColumnType
import org.jetbrains.exposed.sql.LongColumnType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.UUIDColumnType
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.JavaInstantColumnType
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.update
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Exposed-backed [ProgressionRepository]. A near-exact mirror of
 * [PostgresWalletRepository] — XP is the wallet pattern without debits:
 * deltas only accrue (clamped non-negative), so there's no insufficient-funds
 * branch.
 *
 * Concurrency: every multi-statement op runs in one `database.transaction { }`
 * so the ledger rows + total commit together. Idempotency: the
 * `(user_id, idempotency_key)` PK is the dedup boundary, and `ON CONFLICT DO
 * NOTHING` collapses a replay to a no-op, so the whole batch costs a bounded
 * number of round trips no matter how many events it carries.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
@OptIn(ExperimentalTime::class)
class PostgresProgressionRepository(
    private val database: Database,
    private val clock: Clock,
) : ProgressionRepository {

    override suspend fun findOrCreateResult(userId: UserId): FindOrCreateProgressionResult =
        database.transaction {
            val existing = readProgression(userId)
            if (existing != null) {
                FindOrCreateProgressionResult(progression = existing, created = false)
            } else {
                FindOrCreateProgressionResult(progression = create(userId, clock.now()), created = true)
            }
        }

    override suspend fun find(userId: UserId): UserProgression? = database.transaction {
        readProgression(userId)
    }

    override suspend fun applyXpBatch(
        userId: UserId,
        events: List<XpEventInput>,
    ): ApplyXpBatchResult = database.transaction {
        val progression = readProgression(userId) ?: create(userId, clock.now())

        // A client can repeat a key inside one payload; the first occurrence
        // is the one that counts, both for the insert and for the sum.
        val deduped = events.distinctBy { it.idempotencyKey }
        if (deduped.isEmpty()) {
            return@transaction ApplyXpBatchResult(
                totalXp = progression.totalXp,
                appliedKeys = emptySet(),
            )
        }

        val now = clock.now()
        val insertedKeys = deduped
            .chunked(INSERT_CHUNK_ROWS)
            .flatMapTo(mutableSetOf()) { chunk -> insertNewEvents(userId, chunk, now) }

        val gained = deduped
            .filter { it.idempotencyKey in insertedKeys }
            .sumOf { ProgressionRepository.clampEventXp(it.deltaXp) }
        val newTotal = progression.totalXp + gained

        if (insertedKeys.isNotEmpty()) {
            UserProgressionTable.update({ UserProgressionTable.userId eq userId.value }) {
                it[UserProgressionTable.totalXp] = newTotal
                it[UserProgressionTable.updatedAt] = now.toJavaInstant()
            }
        }

        ApplyXpBatchResult(totalXp = newTotal, appliedKeys = insertedKeys)
    }

    /**
     * One `INSERT … ON CONFLICT DO NOTHING RETURNING idempotency_key` for the
     * whole chunk. Raw SQL because Exposed's batch insert can't hand back the
     * rows that actually landed, and that set is what the total moves by —
     * without it a batch racing a concurrent flush of the same keys would
     * double-count them.
     */
    private fun insertNewEvents(
        userId: UserId,
        events: List<XpEventInput>,
        now: kotlin.time.Instant,
    ): List<String> {
        val appliedAt = now.toJavaInstant()
        val values = events.joinToString(",") { "(?,?,?,?,?,?,?,?)" }
        val args = events.flatMap { event ->
            listOf<Pair<IColumnType<*>, Any?>>(
                UUIDColumnType() to userId.value,
                TextColumnType() to event.idempotencyKey,
                LongColumnType() to ProgressionRepository.clampEventXp(event.deltaXp),
                TextColumnType() to event.source,
                TextColumnType() to event.mode,
                TextColumnType() to event.handId,
                BooleanColumnType() to event.wasBoosted,
                JavaInstantColumnType() to appliedAt,
            )
        }
        val inserted = mutableListOf<String>()
        TransactionManager.current().exec(
            stmt = """
                INSERT INTO xp_events
                    (user_id, idempotency_key, delta_xp, source, mode, hand_id, was_boosted, applied_at)
                VALUES $values
                ON CONFLICT (user_id, idempotency_key) DO NOTHING
                RETURNING idempotency_key
            """.trimIndent(),
            args = args,
            explicitStatementType = StatementType.SELECT,
        ) { rs ->
            while (rs.next()) {
                inserted += rs.getString(1)
            }
        }
        return inserted
    }

    override suspend fun recentEvents(userId: UserId, limit: Int): List<XpEvent> =
        database.transaction {
            XpEventsTable
                .selectAll()
                .where { XpEventsTable.userId eq userId.value }
                .orderBy(XpEventsTable.appliedAt to SortOrder.DESC)
                .limit(limit)
                .map { it.toDomain() }
        }

    override suspend fun deleteAllForUser(userId: UserId) {
        database.transaction {
            XpEventsTable.deleteWhere { XpEventsTable.userId eq userId.value }
            UserProgressionTable.deleteWhere { UserProgressionTable.userId eq userId.value }
        }
    }

    private fun readProgression(userId: UserId): UserProgression? = UserProgressionTable
        .selectAll()
        .where { UserProgressionTable.userId eq userId.value }
        .singleOrNull()
        ?.toProgression()

    private fun create(userId: UserId, now: kotlin.time.Instant): UserProgression {
        val javaNow = now.toJavaInstant()
        try {
            UserProgressionTable.insert {
                it[UserProgressionTable.userId] = userId.value
                it[UserProgressionTable.totalXp] = 0L
                it[UserProgressionTable.createdAt] = javaNow
                it[UserProgressionTable.updatedAt] = javaNow
            }
        } catch (e: ExposedSQLException) {
            // Concurrent writer raced us to the lazy-create; their row wins.
            if (!e.isUniqueViolation()) throw e
        }
        return readProgression(userId) ?: error(
            "Progression row missing for user ${userId.value} after lazy-create",
        )
    }

    private fun ResultRow.toProgression(): UserProgression = UserProgression(
        userId = UserId(this[UserProgressionTable.userId]),
        totalXp = this[UserProgressionTable.totalXp],
        createdAt = this[UserProgressionTable.createdAt].toKotlinInstant(),
        updatedAt = this[UserProgressionTable.updatedAt].toKotlinInstant(),
    )

    private fun ResultRow.toDomain(): XpEvent = XpEvent(
        userId = UserId(this[XpEventsTable.userId]),
        idempotencyKey = this[XpEventsTable.idempotencyKey],
        deltaXp = this[XpEventsTable.deltaXp],
        source = this[XpEventsTable.eventSource],
        mode = this[XpEventsTable.mode],
        handId = this[XpEventsTable.handId],
        wasBoosted = this[XpEventsTable.wasBoosted],
        appliedAt = this[XpEventsTable.appliedAt].toKotlinInstant(),
    )

    private fun ExposedSQLException.isUniqueViolation(): Boolean {
        val sqlState = (cause as? java.sql.SQLException)?.sqlState
            ?: (this as? java.sql.SQLException)?.sqlState
        return sqlState == POSTGRES_UNIQUE_VIOLATION_SQLSTATE
    }

    companion object {
        private const val POSTGRES_UNIQUE_VIOLATION_SQLSTATE = "23505"

        /**
         * Rows per `INSERT`. Eight bind parameters each, so this sits an order
         * of magnitude under Postgres's 65,535-parameter ceiling while still
         * collapsing any realistic backlog into a handful of round trips.
         */
        private const val INSERT_CHUNK_ROWS = 500
    }
}
