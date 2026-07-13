package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.PlayerReportsTable
import com.dangerfield.cards.server.db.toJavaInstant
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.PlayerReportRepository
import com.dangerfield.cards.server.domain.UserId
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.sql.insert
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Exposed-backed [PlayerReportRepository]. Append-only inserts into
 * `player_reports`; every read is deferred to the future moderation-review
 * surface (see [PlayerReportsTable]).
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
@OptIn(ExperimentalTime::class)
class PostgresPlayerReportRepository(
    private val database: Database,
    private val clock: Clock,
) : PlayerReportRepository {

    override suspend fun record(
        reporter: UserId,
        reported: UserId,
        inRoom: String?,
        reason: String?,
    ) {
        val now = clock.now().toJavaInstant()
        database.transaction {
            PlayerReportsTable.insert {
                it[reporterUserId] = reporter.value
                it[reportedUserId] = reported.value
                it[roomCode] = inRoom
                it[PlayerReportsTable.reason] = reason
                it[createdAt] = now
            }
        }
    }
}
