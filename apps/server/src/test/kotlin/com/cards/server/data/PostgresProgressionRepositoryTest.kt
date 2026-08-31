package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.db.SqlActivity
import com.dangerfield.cards.server.db.UserProgressionTable
import com.dangerfield.cards.server.db.XpEventsTable
import com.dangerfield.cards.server.domain.ApplyXpOutcome
import com.dangerfield.cards.server.domain.ProgressionRepository
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.XpEventInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import kotlin.time.measureTime
import kotlin.time.Duration.Companion.seconds

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
    fun applyXp_persistsWasBoosted_throughRecentEvents() = runTest {
        val repo = newRepo()
        val userId = newUser()

        repo.applyXp(userId, "boosted", deltaXp = 40, source = "hand", mode = "BOTS", handId = "1", wasBoosted = true)
        repo.applyXp(userId, "plain", deltaXp = 20, source = "hand", mode = "BOTS", handId = "2", wasBoosted = false)

        val events = repo.recentEvents(userId, 10).associateBy { it.idempotencyKey }
        assertEquals(true, events.getValue("boosted").wasBoosted, "boosted flag round-trips through the DB")
        assertEquals(false, events.getValue("plain").wasBoosted)
    }

    @Test
    fun applyXpBatch_twoThousandEvents_costOneTransactionAndAHandfulOfStatements() = runTest {
        // ENG-45: the old path opened a transaction and issued ~4 statements
        // per event, so a 2,703-event backlog took 500s against Supabase. The
        // cost of a batch must not scale with its size.
        val repo = newRepo()
        val userId = newUser()
        val events = (1..2_000).map { xpInput("k$it", deltaXp = 3) }

        SqlActivity.reset()
        val elapsed = measureTime { repo.applyXpBatch(userId, events) }

        assertEquals(1, SqlActivity.commitCount, "the whole batch commits once")
        assertTrue(
            SqlActivity.statementCount <= 12,
            "2,000 events must not cost 2,000 round trips (issued ${SqlActivity.statementCount})",
        )
        assertEquals(6_000L, repo.findOrCreate(userId).totalXp)
        assertTrue(elapsed < 10.seconds, "a full backlog flush stays well inside the client's timeout (took $elapsed)")
    }

    @Test
    fun applyXpBatch_returnsOnlyTheKeysItCommitted() = runTest {
        val repo = newRepo()
        val userId = newUser()
        repo.applyXpBatch(userId, listOf(xpInput("old", deltaXp = 30)))

        val result = repo.applyXpBatch(userId, listOf(xpInput("old", deltaXp = 30), xpInput("new", deltaXp = 12)))

        assertEquals(setOf("new"), result.appliedKeys, "the replayed key is not reported as applied")
        assertEquals(42L, result.totalXp)
    }

    @Test
    fun applyXpBatch_fullReplay_movesTheTotalByZero() = runTest {
        val repo = newRepo()
        val userId = newUser()
        val events = (1..50).map { xpInput("replay$it", deltaXp = 40) }
        repo.applyXpBatch(userId, events)

        val replay = repo.applyXpBatch(userId, events)

        assertTrue(replay.appliedKeys.isEmpty(), "a full replay commits nothing")
        assertEquals(2_000L, replay.totalXp, "a full replay moves the total by zero")
        assertEquals(2_000L, repo.findOrCreate(userId).totalXp)
        assertEquals(50, repo.recentEvents(userId, 200).size, "the ledger keeps one row per key")
    }

    @Test
    fun applyXpBatch_repeatedKeyInsideOneBatch_countsOnce() = runTest {
        val repo = newRepo()
        val userId = newUser()

        val result = repo.applyXpBatch(
            userId,
            listOf(xpInput("dupe", deltaXp = 50), xpInput("dupe", deltaXp = 50)),
        )

        assertEquals(setOf("dupe"), result.appliedKeys)
        assertEquals(50L, result.totalXp, "a key repeated inside one payload is awarded once")
        assertEquals(1, repo.recentEvents(userId, 10).size)
    }

    @Test
    fun applyXpBatch_clampsEveryEvent() = runTest {
        val repo = newRepo()
        val userId = newUser()

        val result = repo.applyXpBatch(
            userId,
            listOf(
                xpInput("huge", deltaXp = ProgressionRepository.MAX_EVENT_XP + 5_000),
                xpInput("negative", deltaXp = -100),
                xpInput("normal", deltaXp = 40),
            ),
        )

        assertEquals(ProgressionRepository.MAX_EVENT_XP + 40, result.totalXp, "every delta is clamped before it counts")
        assertEquals(
            ProgressionRepository.MAX_EVENT_XP,
            repo.recentEvents(userId, 10).single { it.idempotencyKey == "huge" }.deltaXp,
            "the stored row carries the clamped value",
        )
    }

    @Test
    fun applyXpBatch_emptyBatch_lazyCreatesAndReportsTheCurrentTotal() = runTest {
        val repo = newRepo()
        val userId = newUser()

        val result = repo.applyXpBatch(userId, emptyList())

        assertEquals(0L, result.totalXp)
        assertTrue(result.appliedKeys.isEmpty())
        assertNotNull(repo.find(userId), "the hydrate pulse still lazy-creates the row")
    }

    @Test
    fun applyXpBatch_keysAreScopedPerUser() = runTest {
        val repo = newRepo()
        val a = newUser()
        val b = newUser()

        repo.applyXpBatch(a, listOf(xpInput("shared", deltaXp = 10)))
        val forB = repo.applyXpBatch(b, listOf(xpInput("shared", deltaXp = 10)))

        assertEquals(setOf("shared"), forB.appliedKeys, "another user's key is not a replay")
        assertEquals(10L, forB.totalXp)
    }

    /**
     * `RetryPolicy.idempotent()` means the same payload really can be in
     * flight twice — that is what ENG-45's timeouts produced — so the key
     * dedup has to hold across transactions, not just within one.
     */
    @Test
    fun applyXpBatch_concurrentFlushOfTheSameKeys_creditsOnce() = runTest {
        val repo = newRepo()
        val userId = newUser()
        val batch = listOf(xpInput("hand_1", deltaXp = 50))

        val results = awaitAll(
            async(Dispatchers.IO) { repo.applyXpBatch(userId, batch) },
            async(Dispatchers.IO) { repo.applyXpBatch(userId, batch) },
        )

        assertEquals(50L, repo.findOrCreate(userId).totalXp, "the same key credited twice")
        assertEquals(
            1,
            results.count { "hand_1" in it.appliedKeys },
            "exactly one flush may claim the key; the other is a replay",
        )
        assertEquals(1, repo.recentEvents(userId, 10).size)
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

    private fun xpInput(key: String, deltaXp: Long): XpEventInput = XpEventInput(
        idempotencyKey = key,
        deltaXp = deltaXp,
        source = "hand",
        mode = "BOTS",
        handId = key,
    )

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
