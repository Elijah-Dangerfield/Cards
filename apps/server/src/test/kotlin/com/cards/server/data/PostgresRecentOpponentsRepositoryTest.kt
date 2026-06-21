package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.db.RecentlyPlayedWithTable
import com.dangerfield.cards.server.domain.UserId
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Integration tests for the Postgres-backed recently-played-with shelf (real
 * Postgres via testcontainers). Covers recency ordering, per-pair dedup +
 * timestamp bump, the played-with check, and the per-user delete cascade
 * clearing both directions.
 */
@OptIn(ExperimentalTime::class)
class PostgresRecentOpponentsRepositoryTest : DatabaseTest() {

    private val clock = MutableClock(Instant.fromEpochMilliseconds(0))

    @After
    fun cleanTables() {
        database.blockingTransaction { RecentlyPlayedWithTable.deleteAll() }
    }

    @Test
    fun listRecent_isNewestFirst() = runTest {
        val repo = newRepo()
        val me = newUser()
        val a = newUser()
        val b = newUser()
        val c = newUser()

        clock.instant = Instant.fromEpochMilliseconds(1_000)
        repo.recordPlayedTogether(me, a)
        clock.instant = Instant.fromEpochMilliseconds(2_000)
        repo.recordPlayedTogether(me, b)
        clock.instant = Instant.fromEpochMilliseconds(3_000)
        repo.recordPlayedTogether(me, c)

        assertEquals(listOf(c, b, a), repo.listRecent(me, limit = 10))
    }

    @Test
    fun recordPlayedTogether_dedupsAndBumpsRecency() = runTest {
        val repo = newRepo()
        val me = newUser()
        val a = newUser()
        val b = newUser()

        clock.instant = Instant.fromEpochMilliseconds(1_000)
        repo.recordPlayedTogether(me, a)
        clock.instant = Instant.fromEpochMilliseconds(2_000)
        repo.recordPlayedTogether(me, b)
        // Re-playing with `a` bumps it above `b` and keeps a single row.
        clock.instant = Instant.fromEpochMilliseconds(3_000)
        repo.recordPlayedTogether(me, a)

        assertEquals(listOf(a, b), repo.listRecent(me, limit = 10))
        assertEquals(
            2L,
            database.blockingTransaction { RecentlyPlayedWithTable.selectAll().count() },
        )
    }

    @Test
    fun listRecent_honorsLimit() = runTest {
        val repo = newRepo()
        val me = newUser()
        repeat(5) { i ->
            clock.instant = Instant.fromEpochMilliseconds((i + 1) * 1_000L)
            repo.recordPlayedTogether(me, newUser())
        }

        assertEquals(3, repo.listRecent(me, limit = 3).size)
    }

    @Test
    fun listRecent_isScopedPerUser() = runTest {
        val repo = newRepo()
        val me = newUser()
        val you = newUser()
        val a = newUser()

        repo.recordPlayedTogether(me, a)

        assertEquals(listOf(a), repo.listRecent(me, limit = 10))
        assertTrue(repo.listRecent(you, limit = 10).isEmpty(), "the reverse direction wasn't written")
    }

    @Test
    fun hasPlayedWith_reflectsTheRecord() = runTest {
        val repo = newRepo()
        val me = newUser()
        val a = newUser()
        val stranger = newUser()

        repo.recordPlayedTogether(me, a)

        assertTrue(repo.hasPlayedWith(me, a))
        assertFalse(repo.hasPlayedWith(me, stranger))
    }

    @Test
    fun deleteAllForUser_clearsBothDirections_andOnlyThatUser() = runTest {
        val repo = newRepo()
        val a = newUser()
        val b = newUser()
        val c = newUser()
        // Both directions of a↔b, plus an unrelated b↔c pair.
        repo.recordPlayedTogether(a, b)
        repo.recordPlayedTogether(b, a)
        repo.recordPlayedTogether(b, c)
        repo.recordPlayedTogether(c, b)

        repo.deleteAllForUser(a)

        assertTrue(repo.listRecent(a, limit = 10).isEmpty())
        assertFalse(repo.hasPlayedWith(b, a), "b's view of a is gone too")
        // The b↔c pair is untouched.
        assertEquals(listOf(c), repo.listRecent(b, limit = 10))
        assertEquals(listOf(b), repo.listRecent(c, limit = 10))
    }

    private fun newRepo(): PostgresRecentOpponentsRepository =
        PostgresRecentOpponentsRepository(database = database, clock = clock)

    private fun newUser(): UserId = seedAuthUser()

    private class MutableClock(var instant: Instant) : Clock {
        override fun now(): Instant = instant
    }
}
