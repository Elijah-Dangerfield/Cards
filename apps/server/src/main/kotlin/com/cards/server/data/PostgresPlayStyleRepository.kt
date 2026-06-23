package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.PlayStyleEventsTable
import com.dangerfield.cards.server.db.UserPlayStyleAggregateTable
import com.dangerfield.cards.server.db.toJavaInstant
import com.dangerfield.cards.server.db.toKotlinInstant
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.ApplyPlayStyleOutcome
import com.dangerfield.cards.server.domain.PlayStyleHand
import com.dangerfield.cards.server.domain.PlayStyleRepository
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.UserPlayStyleAggregate
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.ResultRow
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
 * Exposed-backed [PlayStyleRepository]. A near-exact mirror of
 * [PostgresProgressionRepository] — one append-only ledger keyed by
 * `(user_id, idempotency_key)` plus a per-user rolling aggregate, both bumped
 * inside one transaction so the row + the sums commit together.
 *
 * Idempotency: the ledger PK is the dedup boundary; a duplicate insert raises
 * a unique-violation we catch and treat as a no-op replay (so the aggregate is
 * never double-bumped for a re-flushed hand).
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

    override suspend fun applyHand(userId: UserId, hand: PlayStyleHand): ApplyPlayStyleOutcome =
        database.transaction {
            val aggregate = readAggregate(userId) ?: create(userId, clock.now())

            // Replay detection: a hand with this key already committed.
            if (eventExists(userId, hand.idempotencyKey)) {
                return@transaction ApplyPlayStyleOutcome(wasAlreadyApplied = true)
            }

            val now = clock.now()
            try {
                PlayStyleEventsTable.insert {
                    it[PlayStyleEventsTable.userId] = userId.value
                    it[idempotencyKey] = hand.idempotencyKey
                    it[mode] = hand.mode
                    it[inBlind] = hand.inBlind
                    it[vpip] = hand.vpip
                    it[pfr] = hand.pfr
                    it[preflopFold] = hand.preflopFold
                    it[aggressiveActionCount] = hand.aggressiveActionCount
                    it[callActionCount] = hand.callActionCount
                    it[wentToShowdown] = hand.wentToShowdown
                    it[showdownBluff] = hand.showdownBluff
                    it[appliedAt] = now.toJavaInstant()
                }
            } catch (e: ExposedSQLException) {
                // A concurrent writer beat us to this key — treat as replay.
                if (e.isUniqueViolation()) {
                    return@transaction ApplyPlayStyleOutcome(wasAlreadyApplied = true)
                }
                throw e
            }

            UserPlayStyleAggregateTable.update({ UserPlayStyleAggregateTable.userId eq userId.value }) {
                it[handsDealt] = aggregate.handsDealt + 1
                it[handsDealtNonBlind] = aggregate.handsDealtNonBlind + if (hand.inBlind) 0 else 1
                it[vpipCount] = aggregate.vpipCount + if (hand.vpip) 1 else 0
                it[pfrCount] = aggregate.pfrCount + if (hand.pfr) 1 else 0
                it[preflopFoldCount] = aggregate.preflopFoldCount + if (hand.preflopFold) 1 else 0
                it[aggressiveActionCount] = aggregate.aggressiveActionCount + hand.aggressiveActionCount
                it[callActionCount] = aggregate.callActionCount + hand.callActionCount
                it[showdownCount] = aggregate.showdownCount + if (hand.wentToShowdown) 1 else 0
                it[showdownBluffCount] = aggregate.showdownBluffCount + if (hand.showdownBluff) 1 else 0
                it[updatedAt] = now.toJavaInstant()
            }

            ApplyPlayStyleOutcome(wasAlreadyApplied = false)
        }

    override suspend fun deleteAllForUser(userId: UserId) {
        database.transaction {
            PlayStyleEventsTable.deleteWhere { PlayStyleEventsTable.userId eq userId.value }
            UserPlayStyleAggregateTable.deleteWhere { UserPlayStyleAggregateTable.userId eq userId.value }
        }
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

    private fun eventExists(userId: UserId, key: String): Boolean = PlayStyleEventsTable
        .selectAll()
        .where {
            (PlayStyleEventsTable.userId eq userId.value) and
                (PlayStyleEventsTable.idempotencyKey eq key)
        }
        .limit(1)
        .any()

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
    }
}
