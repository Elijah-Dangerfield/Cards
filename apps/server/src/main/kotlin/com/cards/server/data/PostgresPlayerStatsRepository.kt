package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.PlayerStatEventsTable
import com.dangerfield.cards.server.db.UserPlayerStatsTable
import com.dangerfield.cards.server.db.toJavaInstant
import com.dangerfield.cards.server.db.toKotlinInstant
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.ApplyPlayerStatOutcome
import com.dangerfield.cards.server.domain.PlayerStatHand
import com.dangerfield.cards.server.domain.PlayerStats
import com.dangerfield.cards.server.domain.PlayerStatsRepository
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.applying
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
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
 * Exposed-backed [PlayerStatsRepository]. A near-exact mirror of
 * [PostgresPlayStyleRepository] — one append-only ledger keyed by
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
class PostgresPlayerStatsRepository(
    private val database: Database,
    private val clock: Clock,
) : PlayerStatsRepository {

    override suspend fun findOrCreate(userId: UserId): PlayerStats = database.transaction {
        read(userId) ?: create(userId, clock.now())
    }

    override suspend fun find(userId: UserId): PlayerStats? = database.transaction {
        read(userId)
    }

    override suspend fun applyHand(userId: UserId, hand: PlayerStatHand): ApplyPlayerStatOutcome =
        database.transaction {
            val stats = read(userId) ?: create(userId, clock.now())

            if (eventExists(userId, hand.idempotencyKey)) {
                return@transaction ApplyPlayerStatOutcome(wasAlreadyApplied = true)
            }

            val now = clock.now()
            try {
                PlayerStatEventsTable.insert {
                    it[PlayerStatEventsTable.userId] = userId.value
                    it[idempotencyKey] = hand.idempotencyKey
                    it[mode] = hand.mode
                    it[won] = hand.won
                    it[folded] = hand.folded
                    it[lostAtShowdown] = hand.lostAtShowdown
                    it[vsBot] = hand.vsBot
                    it[beatenBotId] = hand.beatenBotId
                    it[noBustStreak] = hand.noBustStreak
                    it[appliedAt] = now.toJavaInstant()
                }
            } catch (e: ExposedSQLException) {
                if (e.isUniqueViolation()) {
                    return@transaction ApplyPlayerStatOutcome(wasAlreadyApplied = true)
                }
                throw e
            }

            val next = stats.applying(hand)
            UserPlayerStatsTable.update({ UserPlayerStatsTable.userId eq userId.value }) {
                it[handsPlayed] = next.handsPlayed
                it[handsWon] = next.handsWon
                it[handsFolded] = next.handsFolded
                it[handsLostAtShowdown] = next.handsLostAtShowdown
                it[botHandsPlayed] = next.botHandsPlayed
                it[currentNoBustStreak] = next.currentNoBustStreak
                it[bestNoBustStreak] = next.bestNoBustStreak
                it[perBotWins] = encodePerBotWins(next.perBotWins)
                it[updatedAt] = now.toJavaInstant()
            }

            ApplyPlayerStatOutcome(wasAlreadyApplied = false)
        }

    override suspend fun deleteAllForUser(userId: UserId) {
        database.transaction {
            PlayerStatEventsTable.deleteWhere { PlayerStatEventsTable.userId eq userId.value }
            UserPlayerStatsTable.deleteWhere { UserPlayerStatsTable.userId eq userId.value }
        }
    }

    private fun read(userId: UserId): PlayerStats? = UserPlayerStatsTable
        .selectAll()
        .where { UserPlayerStatsTable.userId eq userId.value }
        .singleOrNull()
        ?.toStats()

    private fun create(userId: UserId, now: kotlin.time.Instant): PlayerStats {
        val javaNow = now.toJavaInstant()
        try {
            UserPlayerStatsTable.insert {
                it[UserPlayerStatsTable.userId] = userId.value
                it[handsPlayed] = 0
                it[handsWon] = 0
                it[handsFolded] = 0
                it[handsLostAtShowdown] = 0
                it[botHandsPlayed] = 0
                it[currentNoBustStreak] = 0
                it[bestNoBustStreak] = 0
                it[perBotWins] = encodePerBotWins(emptyMap())
                it[createdAt] = javaNow
                it[updatedAt] = javaNow
            }
        } catch (e: ExposedSQLException) {
            if (!e.isUniqueViolation()) throw e
        }
        return read(userId) ?: error(
            "Player-stats row missing for user ${userId.value} after lazy-create",
        )
    }

    private fun eventExists(userId: UserId, key: String): Boolean = PlayerStatEventsTable
        .selectAll()
        .where {
            (PlayerStatEventsTable.userId eq userId.value) and
                (PlayerStatEventsTable.idempotencyKey eq key)
        }
        .limit(1)
        .any()

    private fun ResultRow.toStats(): PlayerStats = PlayerStats(
        userId = UserId(this[UserPlayerStatsTable.userId]),
        handsPlayed = this[UserPlayerStatsTable.handsPlayed],
        handsWon = this[UserPlayerStatsTable.handsWon],
        handsFolded = this[UserPlayerStatsTable.handsFolded],
        handsLostAtShowdown = this[UserPlayerStatsTable.handsLostAtShowdown],
        botHandsPlayed = this[UserPlayerStatsTable.botHandsPlayed],
        currentNoBustStreak = this[UserPlayerStatsTable.currentNoBustStreak],
        bestNoBustStreak = this[UserPlayerStatsTable.bestNoBustStreak],
        perBotWins = decodePerBotWins(this[UserPlayerStatsTable.perBotWins]),
        createdAt = this[UserPlayerStatsTable.createdAt].toKotlinInstant(),
        updatedAt = this[UserPlayerStatsTable.updatedAt].toKotlinInstant(),
    )

    private fun encodePerBotWins(map: Map<String, Long>): String =
        json.encodeToString(perBotWinsSerializer, map)

    private fun decodePerBotWins(raw: String): Map<String, Long> =
        if (raw.isBlank()) emptyMap() else json.decodeFromString(perBotWinsSerializer, raw)

    private fun ExposedSQLException.isUniqueViolation(): Boolean {
        val sqlState = (cause as? java.sql.SQLException)?.sqlState
            ?: (this as? java.sql.SQLException)?.sqlState
        return sqlState == POSTGRES_UNIQUE_VIOLATION_SQLSTATE
    }

    companion object {
        private const val POSTGRES_UNIQUE_VIOLATION_SQLSTATE = "23505"
        private val json = Json
        private val perBotWinsSerializer =
            MapSerializer(String.serializer(), Long.serializer())
    }
}
