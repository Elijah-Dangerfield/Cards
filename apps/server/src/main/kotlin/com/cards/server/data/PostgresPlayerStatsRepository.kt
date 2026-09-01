package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.PlayerStatEventsTable
import com.dangerfield.cards.server.db.UserPlayerStatsTable
import com.dangerfield.cards.server.db.toJavaInstant
import com.dangerfield.cards.server.db.toKotlinInstant
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.libraries.achievements.AchievementCounters
import com.dangerfield.cards.libraries.achievements.HandFacts
import com.dangerfield.cards.server.domain.ApplyPlayerStatsBatchResult
import com.dangerfield.cards.server.domain.PlayerStats
import com.dangerfield.cards.server.domain.PlayerStatsRepository
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.perBotWins
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
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
import org.jetbrains.exposed.sql.and
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
 * Exposed-backed [PlayerStatsRepository]. One append-only ledger keyed by
 * `(user_id, idempotency_key)` plus a per-user aggregate, both written inside
 * one transaction so the rows + the counters commit together.
 *
 * Unlike [PostgresPlayStyleRepository] and [PostgresProgressionRepository],
 * this aggregate is not a set of sums: [AchievementCounters.fold] carries
 * streaks, high-water marks and a latch, so the counters can only be produced
 * by replaying hands in arrival order. That rules out a relative SQL update, so
 * a batch instead takes the aggregate row's write lock up front and folds in
 * memory over exactly the hands it committed.
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

    override suspend fun applyHandBatch(
        userId: UserId,
        facts: List<HandFacts>,
    ): ApplyPlayerStatsBatchResult = database.transaction {
        // Lock the aggregate for the whole transaction. A read-then-write of an
        // order-dependent fold can't be made safe by arithmetic the way XP's
        // `total_xp + ?` can (ENG-45), so overlapping flushes for one user have
        // to queue. Postgres raises a serialization error rather than losing a
        // write when they collide, and Exposed retries it.
        val stats = readForUpdate(userId) ?: create(userId, clock.now())

        // A client can repeat a key inside one payload; the first occurrence is
        // the one that counts.
        val deduped = facts.distinctBy { it.idempotencyKey }
        if (deduped.isEmpty()) {
            return@transaction ApplyPlayerStatsBatchResult(stats = stats, appliedKeys = emptySet())
        }

        // Which of these hands are already in the ledger? This has to be known
        // *before* the insert, because each ledger row stores the no-bust streak
        // as of that hand — a fold that re-counted already-applied hands would
        // write an inflated streak onto the new rows.
        val alreadyApplied = existingKeys(userId, deduped.map { it.idempotencyKey })
        val candidates = deduped.filterNot { it.idempotencyKey in alreadyApplied }
        if (candidates.isEmpty()) {
            return@transaction ApplyPlayerStatsBatchResult(stats = stats, appliedKeys = emptySet())
        }

        val streakByKey = mutableMapOf<String, Long>()
        var folded = stats.counters
        candidates.forEach { hand ->
            folded = folded.fold(hand)
            streakByKey[hand.idempotencyKey] = folded[AchievementCounters.NO_BUST_STREAK]
        }

        val now = clock.now()
        val insertedKeys = candidates
            .chunked(INSERT_CHUNK_ROWS)
            .flatMapTo(mutableSetOf()) { chunk -> insertNewEvents(userId, chunk, streakByKey, now) }

        if (insertedKeys.isEmpty()) {
            return@transaction ApplyPlayerStatsBatchResult(stats = stats, appliedKeys = emptySet())
        }

        // The row lock makes this the same set in practice. Re-fold anyway if a
        // key went missing: the aggregate must never count a hand this
        // transaction didn't commit.
        val nextCounters = if (insertedKeys.size == candidates.size) {
            folded
        } else {
            candidates
                .filter { it.idempotencyKey in insertedKeys }
                .fold(stats.counters) { counters, hand -> counters.fold(hand) }
        }

        writeAggregate(userId, nextCounters, now)

        ApplyPlayerStatsBatchResult(
            stats = read(userId) ?: error("Player-stats row missing for user ${userId.value} after write"),
            appliedKeys = insertedKeys,
        )
    }

    override suspend fun deleteAllForUser(userId: UserId) {
        database.transaction {
            PlayerStatEventsTable.deleteWhere { PlayerStatEventsTable.userId eq userId.value }
            UserPlayerStatsTable.deleteWhere { UserPlayerStatsTable.userId eq userId.value }
        }
    }

    /**
     * One `INSERT … ON CONFLICT DO NOTHING RETURNING idempotency_key` for the
     * whole chunk. Raw SQL because Exposed's batch insert can't hand back the
     * rows that actually landed, and that set is what the counters fold over.
     */
    private fun insertNewEvents(
        userId: UserId,
        hands: List<HandFacts>,
        streakByKey: Map<String, Long>,
        now: kotlin.time.Instant,
    ): List<String> {
        val appliedAt = now.toJavaInstant()
        val values = hands.joinToString(",") { "(${"?,".repeat(EVENT_COLUMNS - 1)}?)" }
        val args = hands.flatMap { hand ->
            listOf<Pair<IColumnType<*>, Any?>>(
                UUIDColumnType() to userId.value,
                TextColumnType() to hand.idempotencyKey,
                TextColumnType() to hand.mode,
                BooleanColumnType() to hand.won,
                BooleanColumnType() to hand.folded,
                BooleanColumnType() to hand.lostAtShowdown,
                BooleanColumnType() to hand.vsBot,
                TextColumnType() to hand.beatenBotId,
                // Derived current streak after this hand — back-compat only;
                // the counter fold is the authority now.
                LongColumnType() to (streakByKey[hand.idempotencyKey] ?: 0L),
                BooleanColumnType() to hand.busted,
                LongColumnType() to hand.startStack,
                LongColumnType() to hand.endStack,
                LongColumnType() to hand.bigBlind,
                LongColumnType() to hand.potTotal,
                BooleanColumnType() to hand.wasAllIn,
                BooleanColumnType() to hand.wonByFold,
                IntegerColumnType() to hand.bustsDealt,
                BooleanColumnType() to hand.foldedWouldHaveLost,
                TextColumnType() to hand.handStrengthShown,
                TextColumnType() to hand.botDifficulty,
                JavaInstantColumnType() to appliedAt,
            )
        }
        val inserted = mutableListOf<String>()
        TransactionManager.current().exec(
            stmt = """
                INSERT INTO player_stat_events
                    (user_id, idempotency_key, mode, won, folded, lost_at_showdown, vs_bot,
                     beaten_bot_id, no_bust_streak, busted, start_stack, end_stack, big_blind,
                     pot_total, was_all_in, won_by_fold, busts_dealt, folded_would_have_lost,
                     hand_strength_shown, bot_difficulty, applied_at)
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

    /** The subset of [keys] already in this user's ledger. */
    private fun existingKeys(userId: UserId, keys: List<String>): Set<String> = keys
        .chunked(EXISTS_CHUNK_KEYS)
        .flatMapTo(mutableSetOf()) { chunk ->
            PlayerStatEventsTable
                .select(PlayerStatEventsTable.idempotencyKey)
                .where {
                    (PlayerStatEventsTable.userId eq userId.value) and
                        (PlayerStatEventsTable.idempotencyKey inList chunk)
                }
                .map { it[PlayerStatEventsTable.idempotencyKey] }
        }

    /**
     * The fold is the single projection; the eight headline columns are a
     * denormalized view of its well-known keys, kept for the stats DTO.
     */
    private fun writeAggregate(
        userId: UserId,
        counters: AchievementCounters,
        now: kotlin.time.Instant,
    ) {
        UserPlayerStatsTable.update({ UserPlayerStatsTable.userId eq userId.value }) {
            it[handsPlayed] = counters[AchievementCounters.HANDS_PLAYED]
            it[handsWon] = counters[AchievementCounters.HANDS_WON]
            it[handsFolded] = counters[AchievementCounters.HANDS_FOLDED]
            it[handsLostAtShowdown] = counters[AchievementCounters.HANDS_LOST_AT_SHOWDOWN]
            it[botHandsPlayed] = counters[AchievementCounters.BOT_HANDS_PLAYED]
            it[currentNoBustStreak] = counters[AchievementCounters.NO_BUST_STREAK]
            it[bestNoBustStreak] = counters[AchievementCounters.BEST_NO_BUST_STREAK]
            it[perBotWins] = encodePerBotWins(counters.perBotWins())
            it[achievementCounters] = encodeCounters(counters)
            it[updatedAt] = now.toJavaInstant()
        }
    }

    private fun read(userId: UserId): PlayerStats? = UserPlayerStatsTable
        .selectAll()
        .where { UserPlayerStatsTable.userId eq userId.value }
        .singleOrNull()
        ?.toStats()

    private fun readForUpdate(userId: UserId): PlayerStats? = UserPlayerStatsTable
        .selectAll()
        .where { UserPlayerStatsTable.userId eq userId.value }
        .forUpdate()
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
                it[achievementCounters] = encodeCounters(AchievementCounters.EMPTY)
                it[createdAt] = javaNow
                it[updatedAt] = javaNow
            }
        } catch (e: ExposedSQLException) {
            if (!e.isUniqueViolation()) throw e
        }
        // Re-read under the lock: on the losing side of a lazy-create race the
        // winner's row is the one this batch has to fold onto.
        return readForUpdate(userId) ?: error(
            "Player-stats row missing for user ${userId.value} after lazy-create",
        )
    }

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
        counters = decodeCounters(this[UserPlayerStatsTable.achievementCounters]),
        createdAt = this[UserPlayerStatsTable.createdAt].toKotlinInstant(),
        updatedAt = this[UserPlayerStatsTable.updatedAt].toKotlinInstant(),
    )

    private fun encodePerBotWins(map: Map<String, Long>): String =
        json.encodeToString(perBotWinsSerializer, map)

    private fun decodePerBotWins(raw: String): Map<String, Long> =
        if (raw.isBlank()) emptyMap() else json.decodeFromString(perBotWinsSerializer, raw)

    private fun encodeCounters(counters: AchievementCounters): String =
        json.encodeToString(perBotWinsSerializer, counters.values)

    private fun decodeCounters(raw: String): AchievementCounters =
        if (raw.isBlank()) AchievementCounters.EMPTY
        else AchievementCounters(json.decodeFromString(perBotWinsSerializer, raw))

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

        /** Bind parameters per ledger row — keep in step with the INSERT's column list. */
        private const val EVENT_COLUMNS = 21

        /**
         * Rows per `INSERT`. Twenty-one bind parameters each, so this stays
         * comfortably under Postgres's 65,535-parameter ceiling while still
         * collapsing any realistic backlog into a handful of round trips.
         */
        private const val INSERT_CHUNK_ROWS = 500

        /** Keys per existence probe. One parameter each. */
        private const val EXISTS_CHUNK_KEYS = 1_000
    }
}
