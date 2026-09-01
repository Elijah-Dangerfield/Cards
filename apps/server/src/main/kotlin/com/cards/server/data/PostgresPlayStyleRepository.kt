package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.PlayStyleEventsTable
import com.dangerfield.cards.server.db.UserPlayStyleAggregateTable
import com.dangerfield.cards.server.db.toJavaInstant
import com.dangerfield.cards.server.db.toKotlinInstant
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.ApplyPlayStyleBatchResult
import com.dangerfield.cards.server.domain.PlayStyleHand
import com.dangerfield.cards.server.domain.PlayStyleRepository
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.UserPlayStyleAggregate
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.BooleanColumnType
import org.jetbrains.exposed.sql.IColumnType
import org.jetbrains.exposed.sql.IntegerColumnType
import org.jetbrains.exposed.sql.LongColumnType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.UUIDColumnType
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.JavaInstantColumnType
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.TransactionManager
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Exposed-backed [PlayStyleRepository]. A near-exact mirror of
 * [PostgresProgressionRepository] — one append-only ledger keyed by
 * `(user_id, idempotency_key)` plus a per-user rolling aggregate, both bumped
 * inside one transaction so the rows + the sums commit together.
 *
 * Idempotency: the ledger PK is the dedup boundary, and `ON CONFLICT DO
 * NOTHING` collapses a replay to a no-op, so a whole batch costs a bounded
 * number of round trips no matter how many hands it carries.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
@OptIn(ExperimentalTime::class)
class PostgresPlayStyleRepository(
    private val database: Database,
    private val clock: Clock,
) : PlayStyleRepository {

    override suspend fun findOrCreateAggregate(userId: UserId): UserPlayStyleAggregate =
        database.transaction {
            readAggregate(userId) ?: create(userId, clock.now())
        }

    override suspend fun find(userId: UserId): UserPlayStyleAggregate? = database.transaction {
        readAggregate(userId)
    }

    /**
     * Every play-style counter is an order-independent sum, so a batch is just
     * the ledger insert plus one relative bump of the aggregate by the totals
     * of the hands this transaction actually committed.
     */
    override suspend fun applyHandBatch(
        userId: UserId,
        hands: List<PlayStyleHand>,
    ): ApplyPlayStyleBatchResult = database.transaction {
        val aggregate = readAggregate(userId) ?: create(userId, clock.now())

        // A client can repeat a key inside one payload; the first occurrence
        // is the one that counts, both for the insert and for the sums.
        val deduped = hands.distinctBy { it.idempotencyKey }
        if (deduped.isEmpty()) {
            return@transaction ApplyPlayStyleBatchResult(
                aggregate = aggregate,
                appliedKeys = emptySet(),
            )
        }

        val now = clock.now()
        val insertedKeys = deduped
            .chunked(INSERT_CHUNK_ROWS)
            .flatMapTo(mutableSetOf()) { chunk -> insertNewEvents(userId, chunk, now) }

        val applied = deduped.filter { it.idempotencyKey in insertedKeys }
        val next = if (applied.isEmpty()) aggregate else addToAggregate(userId, applied, now)

        ApplyPlayStyleBatchResult(aggregate = next, appliedKeys = insertedKeys)
    }

    override suspend fun deleteAllForUser(userId: UserId) {
        database.transaction {
            PlayStyleEventsTable.deleteWhere { PlayStyleEventsTable.userId eq userId.value }
            UserPlayStyleAggregateTable.deleteWhere { UserPlayStyleAggregateTable.userId eq userId.value }
        }
    }

    /**
     * One `INSERT … ON CONFLICT DO NOTHING RETURNING idempotency_key` for the
     * whole chunk. Raw SQL because Exposed's batch insert can't hand back the
     * rows that actually landed, and that set is what the aggregate moves by —
     * without it a batch racing a concurrent flush of the same keys would
     * double-count them.
     */
    private fun insertNewEvents(
        userId: UserId,
        hands: List<PlayStyleHand>,
        now: kotlin.time.Instant,
    ): List<String> {
        val appliedAt = now.toJavaInstant()
        val values = hands.joinToString(",") { "(?,?,?,?,?,?,?,?,?,?,?,?)" }
        val args = hands.flatMap { hand ->
            listOf<Pair<IColumnType<*>, Any?>>(
                UUIDColumnType() to userId.value,
                TextColumnType() to hand.idempotencyKey,
                TextColumnType() to hand.mode,
                BooleanColumnType() to hand.inBlind,
                BooleanColumnType() to hand.vpip,
                BooleanColumnType() to hand.pfr,
                BooleanColumnType() to hand.preflopFold,
                IntegerColumnType() to hand.aggressiveActionCount,
                IntegerColumnType() to hand.callActionCount,
                BooleanColumnType() to hand.wentToShowdown,
                BooleanColumnType() to hand.showdownBluff,
                JavaInstantColumnType() to appliedAt,
            )
        }
        val inserted = mutableListOf<String>()
        TransactionManager.current().exec(
            stmt = """
                INSERT INTO play_style_events
                    (user_id, idempotency_key, mode, in_blind, vpip, pfr, preflop_fold,
                     aggressive_action_count, call_action_count, went_to_showdown,
                     showdown_bluff, applied_at)
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

    /**
     * Bumps every counter by the batch's totals and returns what the aggregate
     * became.
     *
     * Relative on purpose. Writing an absolute `read + delta` loses counts
     * whenever two flushes overlap — and they do, because a client that times
     * out retries a request the server is still running (ENG-45). Postgres
     * evaluates `hands_dealt + ?` against the committed row under the UPDATE's
     * own row lock, so the arithmetic holds however the transactions interleave.
     */
    private fun addToAggregate(
        userId: UserId,
        hands: List<PlayStyleHand>,
        now: kotlin.time.Instant,
    ): UserPlayStyleAggregate {
        TransactionManager.current().exec(
            stmt = """
                UPDATE user_play_style_aggregate SET
                    hands_dealt = hands_dealt + ?,
                    hands_dealt_non_blind = hands_dealt_non_blind + ?,
                    vpip_count = vpip_count + ?,
                    pfr_count = pfr_count + ?,
                    preflop_fold_count = preflop_fold_count + ?,
                    aggressive_action_count = aggressive_action_count + ?,
                    call_action_count = call_action_count + ?,
                    showdown_count = showdown_count + ?,
                    showdown_bluff_count = showdown_bluff_count + ?,
                    updated_at = ?
                WHERE user_id = ?
            """.trimIndent(),
            args = listOf<Pair<IColumnType<*>, Any?>>(
                LongColumnType() to hands.size.toLong(),
                LongColumnType() to hands.count { !it.inBlind }.toLong(),
                LongColumnType() to hands.count { it.vpip }.toLong(),
                LongColumnType() to hands.count { it.pfr }.toLong(),
                LongColumnType() to hands.count { it.preflopFold }.toLong(),
                LongColumnType() to hands.sumOf { it.aggressiveActionCount.toLong() },
                LongColumnType() to hands.sumOf { it.callActionCount.toLong() },
                LongColumnType() to hands.count { it.wentToShowdown }.toLong(),
                LongColumnType() to hands.count { it.showdownBluff }.toLong(),
                JavaInstantColumnType() to now.toJavaInstant(),
                UUIDColumnType() to userId.value,
            ),
            explicitStatementType = StatementType.UPDATE,
        )
        // Re-read rather than RETURNING: our own write is visible inside this
        // transaction, and one cheap SELECT beats hand-mapping twelve columns
        // out of a ResultSet twice.
        return readAggregate(userId) ?: error(
            "Play-style aggregate row missing for user ${userId.value} during counter update",
        )
    }

    private fun readAggregate(userId: UserId): UserPlayStyleAggregate? = UserPlayStyleAggregateTable
        .selectAll()
        .where { UserPlayStyleAggregateTable.userId eq userId.value }
        .singleOrNull()
        ?.toAggregate()

    private fun create(userId: UserId, now: kotlin.time.Instant): UserPlayStyleAggregate {
        val javaNow = now.toJavaInstant()
        try {
            UserPlayStyleAggregateTable.insert {
                it[UserPlayStyleAggregateTable.userId] = userId.value
                it[handsDealt] = 0
                it[handsDealtNonBlind] = 0
                it[vpipCount] = 0
                it[pfrCount] = 0
                it[preflopFoldCount] = 0
                it[aggressiveActionCount] = 0
                it[callActionCount] = 0
                it[showdownCount] = 0
                it[showdownBluffCount] = 0
                it[createdAt] = javaNow
                it[updatedAt] = javaNow
            }
        } catch (e: ExposedSQLException) {
            // Concurrent writer raced us to the lazy-create; their row wins.
            if (!e.isUniqueViolation()) throw e
        }
        return readAggregate(userId) ?: error(
            "Play-style aggregate row missing for user ${userId.value} after lazy-create",
        )
    }

    private fun ResultRow.toAggregate(): UserPlayStyleAggregate = UserPlayStyleAggregate(
        userId = UserId(this[UserPlayStyleAggregateTable.userId]),
        handsDealt = this[UserPlayStyleAggregateTable.handsDealt],
        handsDealtNonBlind = this[UserPlayStyleAggregateTable.handsDealtNonBlind],
        vpipCount = this[UserPlayStyleAggregateTable.vpipCount],
        pfrCount = this[UserPlayStyleAggregateTable.pfrCount],
        preflopFoldCount = this[UserPlayStyleAggregateTable.preflopFoldCount],
        aggressiveActionCount = this[UserPlayStyleAggregateTable.aggressiveActionCount],
        callActionCount = this[UserPlayStyleAggregateTable.callActionCount],
        showdownCount = this[UserPlayStyleAggregateTable.showdownCount],
        showdownBluffCount = this[UserPlayStyleAggregateTable.showdownBluffCount],
        createdAt = this[UserPlayStyleAggregateTable.createdAt].toKotlinInstant(),
        updatedAt = this[UserPlayStyleAggregateTable.updatedAt].toKotlinInstant(),
    )

    private fun ExposedSQLException.isUniqueViolation(): Boolean {
        val sqlState = (cause as? java.sql.SQLException)?.sqlState
            ?: (this as? java.sql.SQLException)?.sqlState
        return sqlState == POSTGRES_UNIQUE_VIOLATION_SQLSTATE
    }

    companion object {
        private const val POSTGRES_UNIQUE_VIOLATION_SQLSTATE = "23505"

        /**
         * Rows per `INSERT`. Twelve bind parameters each, so this sits an order
         * of magnitude under Postgres's 65,535-parameter ceiling while still
         * collapsing any realistic backlog into a handful of round trips.
         */
        private const val INSERT_CHUNK_ROWS = 500
    }
}
