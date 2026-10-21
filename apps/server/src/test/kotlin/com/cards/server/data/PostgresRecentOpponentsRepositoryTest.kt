package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.db.RecentlyPlayedWithTable
import com.dangerfield.cards.server.domain.HandTogether
import com.dangerfield.cards.server.domain.UserId
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.junit.After
import java.util.UUID
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
    fun countDistinctOpponents_countsUniquePartners_scopedPerUser() = runTest {
        val repo = newRepo()
        val me = newUser()
        val you = newUser()
        val a = newUser()
        val b = newUser()

        assertEquals(0L, repo.countDistinctOpponents(me), "no hands played yet")

        repo.recordPlayedTogether(me, a)
        repo.recordPlayedTogether(me, b)
        // Re-playing the same opponent must not double-count.
        clock.instant = Instant.fromEpochMilliseconds(5_000)
        repo.recordPlayedTogether(me, a)
        // Another user's pairings don't bleed into my count.
        repo.recordPlayedTogether(you, a)

        assertEquals(2L, repo.countDistinctOpponents(me))
        assertEquals(1L, repo.countDistinctOpponents(you))
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

    @Test
    fun rivalry_accumulatesTheScorelineAcrossHandsAndSessions() = runTest {
        val repo = newRepo()
        val me = newUser()
        val them = newUser()
        val sessionOne = UUID.randomUUID()
        val sessionTwo = UUID.randomUUID()

        repo.recordPlayedTogether(me, them, hand(sessionOne, 1, viewerWon = true, pot = 400))
        repo.recordPlayedTogether(me, them, hand(sessionOne, 2, opponentWon = true, pot = 900))
        // A chop: the pot moved but neither of them finished the hand up.
        repo.recordPlayedTogether(me, them, hand(sessionOne, 3, pot = 1_200))
        repo.recordPlayedTogether(me, them, hand(sessionTwo, 1, viewerWon = true, pot = 2_500))

        val rivalry = repo.listRivalries(me, limit = 10).single()

        assertEquals(them, rivalry.opponentId)
        assertEquals(4, rivalry.handsTogether)
        assertEquals(2, rivalry.handsWon)
        assertEquals(1, rivalry.opponentHandsWon)
        assertEquals(2, rivalry.sessionsTogether, "two tables, four hands")
        assertEquals(
            2_500,
            rivalry.biggestPotWon,
            "only pots the viewer actually took count toward their biggest",
        )
    }

    @Test
    fun rivalry_needsASecondTable_beforeItIsOne() = runTest {
        val repo = newRepo()
        val me = newUser()
        val oneNight = newUser()
        val regular = newUser()
        val session = UUID.randomUUID()

        repeat(12) { hand ->
            repo.recordPlayedTogether(me, oneNight, hand(session, hand, viewerWon = true, pot = 100))
        }
        repo.recordPlayedTogether(me, regular, hand(UUID.randomUUID(), 1, viewerWon = true, pot = 100))
        repo.recordPlayedTogether(me, regular, hand(UUID.randomUUID(), 1, viewerWon = true, pot = 100))

        val rivalries = repo.listRivalries(me, limit = 10)

        assertEquals(
            listOf(regular),
            rivalries.map { it.opponentId },
            "a dozen hands at one table is a game; two tables is a rivalry",
        )
    }

    @Test
    fun recordWithoutAnOutcome_bumpsRecencyAndLeavesTheScorelineAlone() = runTest {
        val repo = newRepo()
        val me = newUser()
        val them = newUser()

        repo.recordPlayedTogether(me, them)
        repo.recordPlayedTogether(me, them)

        assertEquals(listOf(them), repo.listRecent(me, limit = 10))
        assertTrue(
            repo.listRivalries(me, limit = 10).isEmpty(),
            "no hand means no record, not a record of zeroes",
        )
    }

    @Test
    fun preMigrationPair_startsCountingFromItsNextHand() = runTest {
        // V91 defaults an existing row's counters to zero, so the first hand
        // recorded against it must look like a first hand rather than inherit a
        // session count the row never had.
        val repo = newRepo()
        val me = newUser()
        val them = newUser()
        repo.recordPlayedTogether(me, them)

        repo.recordPlayedTogether(me, them, hand(UUID.randomUUID(), 1, viewerWon = true, pot = 300))

        val row = database.blockingTransaction {
            RecentlyPlayedWithTable
                .selectAll()
                .where { RecentlyPlayedWithTable.userId eq me.value }
                .single()
        }
        assertEquals(1, row[RecentlyPlayedWithTable.handsTogether])
        assertEquals(1, row[RecentlyPlayedWithTable.sessionsTogether])
    }

    private fun hand(
        sessionId: UUID,
        handNumber: Int,
        viewerWon: Boolean = false,
        opponentWon: Boolean = false,
        pot: Long = 0,
    ) = HandTogether(
        sessionId = sessionId,
        handNumber = handNumber,
        viewerWon = viewerWon,
        opponentWon = opponentWon,
        potTotal = pot,
    )

    private fun newRepo(): PostgresRecentOpponentsRepository =
        PostgresRecentOpponentsRepository(database = database, clock = clock)

    private fun newUser(): UserId = seedAuthUser()

    private class MutableClock(var instant: Instant) : Clock {
        override fun now(): Instant = instant
    }
}
