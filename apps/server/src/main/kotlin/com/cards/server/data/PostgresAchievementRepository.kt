package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.AchievementsEarnedTable
import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.toJavaInstant
import com.dangerfield.cards.server.db.toKotlinInstant
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.AchievementRepository
import com.dangerfield.cards.server.domain.EarnedAchievement
import com.dangerfield.cards.server.domain.UserId
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Exposed-backed [AchievementRepository]. The earned set is an idempotent
 * upsert keyed on `(user_id, achievement_id)` — mirrors
 * [PostgresInventoryRepository]'s first-write-wins shape (achievements are
 * monotonic, so there's no update path: once earned, always earned).
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
@OptIn(ExperimentalTime::class)
class PostgresAchievementRepository(
    private val database: Database,
) : AchievementRepository {

    override suspend fun recordEarned(
        userId: UserId,
        achievementId: String,
        earnedAt: Instant,
    ): EarnedAchievement = database.transaction {
        val existing = readEarned(userId, achievementId)
        if (existing != null) return@transaction existing

        try {
            AchievementsEarnedTable.insert {
                it[AchievementsEarnedTable.userId] = userId.value
                it[AchievementsEarnedTable.achievementId] = achievementId
                it[AchievementsEarnedTable.earnedAt] = earnedAt.toJavaInstant()
            }
        } catch (e: ExposedSQLException) {
            // Concurrent writer raced us to the same (user, achievement);
            // their row wins. Re-read and return it.
            if (!e.isUniqueViolation()) throw e
        }
        readEarned(userId, achievementId) ?: error(
            "Earned row missing for ($userId, $achievementId) after insert",
        )
    }

    override suspend fun listEarned(userId: UserId): List<EarnedAchievement> =
        database.transaction {
            AchievementsEarnedTable
                .selectAll()
                .where { AchievementsEarnedTable.userId eq userId.value }
                .map { it.toDomain() }
        }

    override suspend fun deleteAllForUser(userId: UserId) {
        database.transaction {
            AchievementsEarnedTable.deleteWhere { AchievementsEarnedTable.userId eq userId.value }
        }
    }

    private fun readEarned(userId: UserId, achievementId: String): EarnedAchievement? =
        AchievementsEarnedTable
            .selectAll()
            .where {
                (AchievementsEarnedTable.userId eq userId.value) and
                    (AchievementsEarnedTable.achievementId eq achievementId)
            }
            .singleOrNull()
            ?.toDomain()

    private fun ResultRow.toDomain(): EarnedAchievement = EarnedAchievement(
        achievementId = this[AchievementsEarnedTable.achievementId],
        earnedAt = this[AchievementsEarnedTable.earnedAt].toKotlinInstant(),
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
