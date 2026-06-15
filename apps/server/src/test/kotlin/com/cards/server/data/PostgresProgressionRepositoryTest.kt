package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.db.UserProgressionTable
import com.dangerfield.cards.server.db.XpEventsTable
import com.dangerfield.cards.server.domain.ApplyXpOutcome
import com.dangerfield.cards.server.domain.ProgressionRepository
import com.dangerfield.cards.server.domain.UserId
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.deleteAll
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Integration tests for the Postgres-backed progression repo (real Postgres
 * via testcontainers), mirroring [PostgresWalletRepositoryTest]. Covers the
 * `(user_id, idempotency_key)` PK dedup, the per-event clamp, lazy-create,
 * and ordered ledger reads.
 */
@OptIn(ExperimentalTime::class)
class PostgresProgressionRepositoryTest : DatabaseTest() {

    @After
    fun cleanTables() {
        database.blockingTransaction {
            XpEventsTable.deleteAll()
            UserProgressionTable.deleteAll()
        }
    }

    @Test
    fun findOrCreate_firstCall_lazySeedsZero() = runTest {
        val repo = newRepo()
        val userId = newUser()

        val progression = repo.findOrCreate(userId)

        assertEquals(userId, progression.userId)
        assertEquals(0L, progression.totalXp)
    }

    @Test
    fun findOrCreateResult_reportsCreatedThenFalse() = runTest {
        val repo = newRepo()
        val userId = newUser()

        assertTrue(repo.findOrCreateResult(userId).created)
        assertFalse(repo.findOrCreateResult(userId).created)
    }

    @Test
    fun find_returnsNull_whenNoRowYet() = runTest {
        assertNull(newRepo().find(newUser()))
    }

    @Test
    fun applyXp_accumulatesTotal_andRecordsEvent() = runTest {
        val repo = newRepo()
        val userId = newUser()

        val outcome = repo.applyXp(userId, "hand_1", deltaXp = 40, source = "hand", mode = "BOTS", handId = "1")

        assertTrue(outcome is ApplyXpOutcome.Applied && !outcome.wasAlreadyApplied)
        assertEquals(40L, outcome.totalXp)
        assertEquals(40L, repo.findOrCreate(userId).totalXp)
    }

    @Test
    fun applyXp_isIdempotent_onKey() = runTest {
        val repo = newRepo()
        val userId = newUser()

        val first = repo.applyXp(userId, "hand_dup", deltaXp = 25, source = "hand", mode = "BOTS", handId = "h")
        val second = repo.applyXp(userId, "hand_dup", deltaXp = 25, source = "hand", mode = "BOTS", handId = "h")

        assertTrue(first is ApplyXpOutcome.Applied && !first.wasAlreadyApplied)
        assertTrue(second is ApplyXpOutcome.Applied && second.wasAlreadyApplied)
        assertEquals(25L, second.totalXp, "replay does not double-count")
    }

    @Test
    fun applyXp_keyScopedPerUser() = runTest {
        val repo = newRepo()
        val a = newUser()
        val b = newUser()

        val applyA = repo.applyXp(a, "shared", deltaXp = 10, source = "hand", mode = "BOTS", handId = null)
        val applyB = repo.applyXp(b, "shared", deltaXp = 10, source = "hand", mode = "BOTS", handId = null)

        assertTrue(applyA is ApplyXpOutcome.Applied && !applyA.wasAlreadyApplied)
        assertTrue(applyB is ApplyXpOutcome.Applied && !applyB.wasAlreadyApplied)
    }

    @Test
    fun applyXp_clampsAboveMax() = runTest {
        val repo = newRepo()
        val userId = newUser()

        val outcome = repo.applyXp(
            userId, "huge", deltaXp = ProgressionRepository.MAX_EVENT_XP + 5_000,
            source = "hand", mode = "BOTS", handId = null,
        )

        assertEquals(ProgressionRepository.MAX_EVENT_XP, outcome.totalXp, "delta is clamped to the sanity cap")
    }

    @Test
    fun applyXp_clampsNegativeToZero() = runTest {
        val repo = newRepo()
        val userId = newUser()

        val outcome = repo.applyXp(userId, "neg", deltaXp = -100, source = "hand", mode = "BOTS", handId = null)

        assertEquals(0L, outcome.totalXp, "negative deltas clamp to zero — XP only accrues")
    }

    @Test
    fun recentEvents_orderedNewestFirst_andCapped() = runTest {
        val clock = SteppingClock(start = Instant.fromEpochMilliseconds(1_700_000_000_000))
        val repo = newRepo(clock = clock)
        val userId = newUser()

        repo.applyXp(userId, "e1", deltaXp = 1, source = "hand", mode = "BOTS", handId = "1")
        repo.applyXp(userId, "e2", deltaXp = 1, source = "hand", mode = "BOTS", handId = "2")
        repo.applyXp(userId, "e3", deltaXp = 1, source = "hand", mode = "BOTS", handId = "3")

        val events = repo.recentEvents(userId, 2)
        assertEquals(2, events.size)
        assertEquals(listOf("e3", "e2"), events.map { it.idempotencyKey })
    }

    @Test
    fun deleteAllForUser_clears_andOnlyTheGivenUser() = runTest {
        val repo = newRepo()
        val a = newUser()
        val b = newUser()
        repo.applyXp(a, "ea", deltaXp = 10, source = "hand", mode = "BOTS", handId = null)
        repo.applyXp(b, "eb", deltaXp = 20, source = "hand", mode = "BOTS", handId = null)

        repo.deleteAllForUser(a)

        assertNull(repo.find(a))
        assertEquals(0, repo.recentEvents(a, 10).size)
        assertNotNull(repo.find(b))
        assertEquals(20L, repo.findOrCreate(b).totalXp)
    }

    private fun newRepo(clock: Clock = Clock.System): PostgresProgressionRepository =
        PostgresProgressionRepository(database = database, clock = clock)

    /** Mints a fresh UUID + seeds the matching auth.users row for the FK. */
    private fun newUser(): UserId = seedAuthUser()

    private class SteppingClock(start: Instant) : Clock {
        private var nowInstant = start
        override fun now(): Instant {
            val current = nowInstant
            nowInstant = current.plus(kotlin.time.Duration.parse("1ms"))
            return current
        }
    }
}
