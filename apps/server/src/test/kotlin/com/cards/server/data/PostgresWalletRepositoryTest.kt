package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.db.WalletEventsTable
import com.dangerfield.cards.server.db.WalletsTable
import com.dangerfield.cards.server.domain.ApplyOutcome
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.Wallet
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.deleteAll
import org.junit.After
import java.util.UUID
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
 * Integration tests for the Postgres-backed wallet repo. Hits real
 * Postgres via testcontainers so the `CHECK (balance >= 0)` constraint,
 * the `(user_id, idempotency_key)` primary key, and the ordered ledger
 * reads are all under test.
 *
 * Each test cleans the wallet tables in `@After` so tests can share the
 * class-level database. Profiles are not touched — wallet rows live
 * independently of profile rows (no FK).
 */
@OptIn(ExperimentalTime::class)
class PostgresWalletRepositoryTest : DatabaseTest() {

    @After
    fun cleanTables() {
        database.blockingTransaction {
            WalletEventsTable.deleteAll()
            WalletsTable.deleteAll()
        }
    }

    @Test
    fun findOrCreate_firstCall_lazySeedsStarterGrant() = runTest {
        val repo = newRepo()
        val userId = newUser()

        val wallet = repo.findOrCreate(userId)

        assertEquals(userId, wallet.userId)
        assertEquals(Wallet.STARTER_GRANT, wallet.balance)
        assertEquals(wallet.createdAt, wallet.updatedAt)
    }

    @Test
    fun findOrCreate_secondCall_doesNotResetBalance() = runTest {
        val repo = newRepo()
        val userId = newUser()
        repo.findOrCreate(userId)
        repo.apply(userId, "evt_1", delta = 500, reason = "test")

        val wallet = repo.findOrCreate(userId)

        assertEquals(Wallet.STARTER_GRANT + 500, wallet.balance)
    }

    @Test
    fun findOrCreateResult_firstCall_createdTrue() = runTest {
        val repo = newRepo()
        val userId = newUser()

        val result = repo.findOrCreateResult(userId)

        assertTrue(result.created)
        assertEquals(Wallet.STARTER_GRANT, result.wallet.balance)
    }

    @Test
    fun findOrCreateResult_secondCall_createdFalse() = runTest {
        val repo = newRepo()
        val userId = newUser()
        repo.findOrCreateResult(userId)

        val result = repo.findOrCreateResult(userId)

        assertFalse(result.created)
    }

    @Test
    fun find_returnsNull_whenWalletDoesNotExistYet() = runTest {
        val repo = newRepo()
        assertNull(repo.find(newUser()))
    }

    @Test
    fun apply_creditsBalance_andRecordsEvent() = runTest {
        val repo = newRepo()
        val userId = newUser()

        val outcome = repo.apply(userId, "evt_credit", delta = 250, reason = "iap.chip_pack_small")

        assertTrue(outcome is ApplyOutcome.Applied)
        assertEquals(Wallet.STARTER_GRANT + 250, outcome.balance)
        assertFalse(outcome.wasAlreadyApplied)
        assertEquals(Wallet.STARTER_GRANT + 250, repo.findOrCreate(userId).balance)
    }

    @Test
    fun apply_isIdempotent_onIdempotencyKey() = runTest {
        val repo = newRepo()
        val userId = newUser()

        val first = repo.apply(userId, "evt_dup", delta = 100, reason = "test")
        val second = repo.apply(userId, "evt_dup", delta = 100, reason = "test")

        assertTrue(first is ApplyOutcome.Applied && !first.wasAlreadyApplied)
        assertTrue(second is ApplyOutcome.Applied && second.wasAlreadyApplied)
        assertEquals(Wallet.STARTER_GRANT + 100, second.balance)
    }

    @Test
    fun apply_idempotencyKey_isScopedPerUser() = runTest {
        // Two users hitting the same idempotency key are NOT a collision —
        // the key is uniquely scoped to (userId, key). Each user gets
        // their own delta applied.
        val repo = newRepo()
        val a = newUser()
        val b = newUser()

        val applyA = repo.apply(a, "shared_key", delta = 100, reason = "test")
        val applyB = repo.apply(b, "shared_key", delta = 100, reason = "test")

        assertTrue(applyA is ApplyOutcome.Applied && !applyA.wasAlreadyApplied)
        assertTrue(applyB is ApplyOutcome.Applied && !applyB.wasAlreadyApplied)
    }

    @Test
    fun apply_rejectsDebit_thatWouldGoNegative() = runTest {
        val repo = newRepo()
        val userId = newUser()

        // Starter grant is 10K — try to debit 20K.
        val outcome = repo.apply(userId, "evt_underflow", delta = -20_000, reason = "test")

        assertTrue(outcome is ApplyOutcome.InsufficientChips)
        assertEquals(Wallet.STARTER_GRANT, outcome.balance)
        // The ledger row must not exist — a rejected attempt is not journaled.
        assertEquals(0, repo.recentEvents(userId, 10).size)
    }

    @Test
    fun apply_allowsDebit_thatLandsAtExactlyZero() = runTest {
        val repo = newRepo()
        val userId = newUser()

        val outcome = repo.apply(userId, "evt_drain", delta = -Wallet.STARTER_GRANT, reason = "test")

        assertTrue(outcome is ApplyOutcome.Applied)
        assertEquals(0L, outcome.balance)
    }

    @Test
    fun apply_acceptsLargeCredit() = runTest {
        val repo = newRepo()
        val userId = newUser()

        val outcome = repo.apply(userId, "evt_big_credit", delta = 1_000_000, reason = "test")

        assertTrue(outcome is ApplyOutcome.Applied)
        assertEquals(Wallet.STARTER_GRANT + 1_000_000, outcome.balance)
    }

    @Test
    fun apply_appliedEvents_areOrderedNewestFirst() = runTest {
        val clock = SteppingClock(start = Instant.fromEpochMilliseconds(1_700_000_000_000))
        val repo = newRepo(clock = clock)
        val userId = newUser()

        repo.apply(userId, "evt_1", delta = 100, reason = "first")
        repo.apply(userId, "evt_2", delta = 200, reason = "second")
        repo.apply(userId, "evt_3", delta = 300, reason = "third")

        val events = repo.recentEvents(userId, 10)
        assertEquals(listOf("third", "second", "first"), events.map { it.reason })
    }

    @Test
    fun recentEvents_isCappedByLimit() = runTest {
        val clock = SteppingClock(start = Instant.fromEpochMilliseconds(1_700_000_000_000))
        val repo = newRepo(clock = clock)
        val userId = newUser()

        repeat(5) { i -> repo.apply(userId, "evt_$i", delta = 1, reason = "e$i") }

        val events = repo.recentEvents(userId, 2)
        assertEquals(2, events.size)
        assertEquals("e4", events.first().reason)
    }

    @Test
    fun deleteAllForUser_clearsWalletAndEvents() = runTest {
        val repo = newRepo()
        val userId = newUser()
        repo.findOrCreate(userId)
        repo.apply(userId, "evt", delta = 100, reason = "test")

        repo.deleteAllForUser(userId)

        assertNull(repo.find(userId))
        assertEquals(0, repo.recentEvents(userId, 10).size)
    }

    @Test
    fun deleteAllForUser_onlyTouchesTheGivenUser() = runTest {
        val repo = newRepo()
        val a = newUser()
        val b = newUser()
        repo.apply(a, "evt_a", delta = 100, reason = "test")
        repo.apply(b, "evt_b", delta = 200, reason = "test")

        repo.deleteAllForUser(a)

        assertNull(repo.find(a))
        val bWallet = repo.find(b)
        assertNotNull(bWallet)
        assertEquals(Wallet.STARTER_GRANT + 200, bWallet.balance)
    }

    @Test
    fun deleteAllForUser_isIdempotent_whenNothingToDelete() = runTest {
        val repo = newRepo()
        // No wallet row yet — must not throw.
        repo.deleteAllForUser(newUser())
    }

    private fun newRepo(clock: Clock = Clock.System): PostgresWalletRepository =
        PostgresWalletRepository(database = database, clock = clock)

    /** Mints a fresh UUID + seeds the matching auth.users row so the
     *  V11 FK on wallets / wallet_events is satisfied. */
    private fun newUser(): UserId = seedAuthUser()

    /**
     * Hand-cranked Clock that advances 1ms on each `now()` call so the
     * `applied_at` timestamps in the ledger are strictly increasing
     * within a test. Without this, two writes in the same millisecond
     * would tie on `ORDER BY applied_at DESC` and the test would be
     * non-deterministic.
     */
    private class SteppingClock(start: Instant) : Clock {
        private var nowInstant = start
        override fun now(): Instant {
            val current = nowInstant
            nowInstant = current.plus(kotlin.time.Duration.parse("1ms"))
            return current
        }
    }
}
