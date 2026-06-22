package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.RecentlyPlayedWithTable
import com.dangerfield.cards.server.db.toJavaInstant
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.RecentOpponentsRepository
import com.dangerfield.cards.server.domain.UserId
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.countDistinct
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Exposed-backed [RecentOpponentsRepository]. Directed rows keyed by
 * `(user_id, opponent_id)`; a shared hand bumps `last_played_at` so the pair
 * stays a single row and the shelf reads newest-first off the recency index.
 *
 * Upsert is two-statement (UPDATE then INSERT-on-zero-rows) inside one
 * transaction — same shape as [com.dangerfield.cards.server.game.PostgresSessionSnapshotStore],
 * so a concurrent writer for the same pair can't slip between the read and the
 * write.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
@OptIn(ExperimentalTime::class)
class PostgresRecentOpponentsRepository(
    private val database: Database,
    private val clock: Clock,
) : RecentOpponentsRepository {

    override suspend fun recordPlayedTogether(userId: UserId, opponentId: UserId) {
        if (userId == opponentId) return
        val now = clock.now().toJavaInstant()
        database.transaction {
            val updated = RecentlyPlayedWithTable.update(
                {
                    (RecentlyPlayedWithTable.userId eq userId.value) and
                        (RecentlyPlayedWithTable.opponentId eq opponentId.value)
                },
            ) {
                it[lastPlayedAt] = now
            }
            if (updated == 0) {
                RecentlyPlayedWithTable.insert {
                    it[RecentlyPlayedWithTable.userId] = userId.value
                    it[RecentlyPlayedWithTable.opponentId] = opponentId.value
                    it[lastPlayedAt] = now
                }
            }
        }
    }

    override suspend fun listRecent(userId: UserId, limit: Int): List<UserId> = database.transaction {
        RecentlyPlayedWithTable
            .selectAll()
            .where { RecentlyPlayedWithTable.userId eq userId.value }
            .orderBy(RecentlyPlayedWithTable.lastPlayedAt to SortOrder.DESC)
            .limit(limit)
            .map { UserId(it[RecentlyPlayedWithTable.opponentId]) }
    }

    override suspend fun countDistinctOpponents(userId: UserId): Long = database.transaction {
        val distinct = RecentlyPlayedWithTable.opponentId.countDistinct()
        RecentlyPlayedWithTable
            .select(distinct)
            .where { RecentlyPlayedWithTable.userId eq userId.value }
            .firstOrNull()
            ?.get(distinct)
            ?: 0L
    }

    override suspend fun hasPlayedWith(userId: UserId, opponentId: UserId): Boolean = database.transaction {
        RecentlyPlayedWithTable
            .selectAll()
            .where {
                (RecentlyPlayedWithTable.userId eq userId.value) and
                    (RecentlyPlayedWithTable.opponentId eq opponentId.value)
            }
            .limit(1)
            .any()
    }

    override suspend fun deleteAllForUser(userId: UserId) {
        database.transaction {
            RecentlyPlayedWithTable.deleteWhere {
                (RecentlyPlayedWithTable.userId eq userId.value) or
                    (RecentlyPlayedWithTable.opponentId eq userId.value)
            }
        }
    }
}
