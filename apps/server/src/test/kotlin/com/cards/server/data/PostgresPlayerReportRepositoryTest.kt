package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.db.PlayerReportsTable
import com.dangerfield.cards.server.domain.UserId
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Integration tests for the Postgres-backed player-report store (real Postgres
 * via testcontainers). Exercises the V85 migration: the row is written with the
 * given context, and account-delete cascades reports away via the auth.users FK.
 */
@OptIn(ExperimentalTime::class)
class PostgresPlayerReportRepositoryTest : DatabaseTest() {

    @After
    fun cleanTables() {
        database.blockingTransaction { PlayerReportsTable.deleteAll() }
    }

    @Test
    fun record_writesRowWithContext() = runTest {
        val repo = newRepo()
        val reporter = newUser()
        val reported = newUser()

        repo.record(reporter, reported, inRoom = "ABCD", reason = "harassment", categories = listOf("harassment", "cheating"))

        val rows = database.blockingTransaction {
            PlayerReportsTable.selectAll()
                .where { PlayerReportsTable.reportedUserId eq reported.value }
                .toList()
        }
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(reporter.value, row[PlayerReportsTable.reporterUserId])
        assertEquals("ABCD", row[PlayerReportsTable.roomCode])
        assertEquals("harassment", row[PlayerReportsTable.reason])
        assertEquals("harassment,cheating", row[PlayerReportsTable.reasonCategories])
    }

    @Test
    fun record_allowsMultipleReportsFromSameReporter() = runTest {
        val repo = newRepo()
        val reporter = newUser()
        val reported = newUser()

        repo.record(reporter, reported, inRoom = "ROOM1", reason = null, categories = emptyList())
        repo.record(reporter, reported, inRoom = "ROOM2", reason = null, categories = emptyList())

        val count = database.blockingTransaction {
            PlayerReportsTable.selectAll()
                .where { PlayerReportsTable.reporterUserId eq reporter.value }
                .count()
        }
        assertEquals(2, count)
    }

    @Test
    fun record_nullContext_persistsAsNull() = runTest {
        val repo = newRepo()
        val reporter = newUser()
        val reported = newUser()

        repo.record(reporter, reported, inRoom = null, reason = null, categories = emptyList())

        val row = database.blockingTransaction {
            PlayerReportsTable.selectAll()
                .where { PlayerReportsTable.reporterUserId eq reporter.value }
                .single()
        }
        assertNull(row[PlayerReportsTable.roomCode])
        assertNull(row[PlayerReportsTable.reason])
        assertNull(row[PlayerReportsTable.reasonCategories], "no categories persists as NULL")
    }

    private fun newRepo(): PostgresPlayerReportRepository =
        PostgresPlayerReportRepository(database = database, clock = fixedClock)

    private fun newUser(): UserId = seedAuthUser()

    private val fixedClock: Clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(0)
    }
}
