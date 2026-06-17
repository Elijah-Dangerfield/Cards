package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.UserProgressionTable
import com.dangerfield.cards.server.db.XpEventsTable
import com.dangerfield.cards.server.db.toJavaInstant
import com.dangerfield.cards.server.db.toKotlinInstant
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.ApplyXpOutcome
import com.dangerfield.cards.server.domain.FindOrCreateProgressionResult
import com.dangerfield.cards.server.domain.ProgressionRepository
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.UserProgression
import com.dangerfield.cards.server.domain.XpEvent
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
 * Exposed-backed [ProgressionRepository]. A near-exact mirror of
 * [PostgresWalletRepository] — XP is the wallet pattern without debits:
 * deltas only accrue (clamped non-negative), so there's no insufficient-funds
 * branch.
 *
 * Concurrency: every multi-statement op runs in one `database.transaction { }`
 * so the ledger row + total commit together. Idempotency: the
 * `(user_id, idempotency_key)` PK is the dedup boundary; a duplicate insert
 * raises a unique-violation we catch and treat as a no-op replay.
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

    override suspend fun applyXp(
        userId: UserId,
        idempotencyKey: String,
        deltaXp: Long,
        source: String,
        mode: String,
        handId: String?,
        wasBoosted: Boolean,
    ): ApplyXpOutcome = database.transaction {
        val progression = readProgression(userId) ?: create(userId, clock.now())

        // Replay detection: an event with this key already committed.
        if (eventExists(userId, idempotencyKey)) {
            return@transaction ApplyXpOutcome.Applied(
                totalXp = progression.totalXp,
                wasAlreadyApplied = true,
            )
        }

        // Cheap per-event sanity clamp — XP only accrues; never negative,
        // never absurd. Not a balance lever (see MAX_EVENT_XP).
        val clamped = deltaXp.coerceIn(0L, ProgressionRepository.MAX_EVENT_XP)
        val newTotal = progression.totalXp + clamped
        val now = clock.now()

        try {
            XpEventsTable.insert {
                it[XpEventsTable.userId] = userId.value
                it[XpEventsTable.idempotencyKey] = idempotencyKey
                it[XpEventsTable.deltaXp] = clamped
                it[XpEventsTable.eventSource] = source
                it[XpEventsTable.mode] = mode
                it[XpEventsTable.handId] = handId
                it[XpEventsTable.wasBoosted] = wasBoosted
                it[XpEventsTable.appliedAt] = now.toJavaInstant()
            }
        } catch (e: ExposedSQLException) {
            // A concurrent writer beat us to this key — treat as replay.
            if (e.isUniqueViolation()) {
                return@transaction ApplyXpOutcome.Applied(
                    totalXp = progression.totalXp,
                    wasAlreadyApplied = true,
                )
            }
            throw e
        }

        UserProgressionTable.update({ UserProgressionTable.userId eq userId.value }) {
            it[UserProgressionTable.totalXp] = newTotal
            it[UserProgressionTable.updatedAt] = now.toJavaInstant()
        }

        ApplyXpOutcome.Applied(totalXp = newTotal, wasAlreadyApplied = false)
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

    private fun eventExists(userId: UserId, key: String): Boolean = XpEventsTable
        .selectAll()
        .where {
            (XpEventsTable.userId eq userId.value) and
                (XpEventsTable.idempotencyKey eq key)
        }
        .limit(1)
        .any()

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
    }
}
