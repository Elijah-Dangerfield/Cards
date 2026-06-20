package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.db.HandFinishedEventsTable
import com.dangerfield.cards.server.domain.UserId
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.deleteAll
import org.junit.After
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Integration tests for the Postgres-backed finished-hand ledger (real
 * Postgres via testcontainers), mirroring [PostgresProgressionRepositoryTest].
 * Covers the `(user_id, idempotency_key)` PK dedup (so a replayed
 * hand-completion never double-counts the hand or its outcome signals),
 * per-user scoping, the cumulative busts / wins-by-fold tallies, and the
 * delete cascade.
 */
@OptIn(ExperimentalTime::class)
class PostgresHandsFinishedRepositoryTest : DatabaseTest() {

    @After
    fun cleanTables() {
        database.blockingTransaction {
            HandFinishedEventsTable.deleteAll()
        }
    }

    @Test
    fun recordHandFinished_incrementsCount() = runTest {
        val repo = newRepo()
        val user = newUser()

        repo.record(user, key(handNumber = 1), handNumber = 1)
        repo.record(user, key(handNumber = 2), handNumber = 2)

        assertEquals(2L, repo.countForUser(user))
    }

    @Test
    fun recordHandFinished_isIdempotentOnKey() = runTest {
        val repo = newRepo()
        val user = newUser()
        val session = UUID.randomUUID()
        val sameKey = "$session:7:${user.value}"

        repo.record(user, sameKey, session, handNumber = 7, bustsDealt = 2, wonByFold = true)
        repo.record(user, sameKey, session, handNumber = 7, bustsDealt = 2, wonByFold = true)

        assertEquals(1L, repo.countForUser(user), "a replayed hand-completion does not double-count")
        assertEquals(2L, repo.bustsDealtForUser(user), "a replay does not double the bust tally")
        assertEquals(1L, repo.winsByFoldForUser(user), "a replay does not double the win-by-fold tally")
    }

    @Test
    fun countForUser_isScopedPerUser() = runTest {
        val repo = newRepo()
        val a = newUser()
        val b = newUser()

        repo.record(a, key(1), handNumber = 1)
        repo.record(a, key(2), handNumber = 2)
        repo.record(b, key(1), handNumber = 1)

        assertEquals(2L, repo.countForUser(a))
        assertEquals(1L, repo.countForUser(b))
    }

    @Test
    fun countForUser_isZero_whenNoRows() = runTest {
        assertEquals(0L, newRepo().countForUser(newUser()))
    }

    @Test
    fun bustsDealtForUser_sumsAcrossHands_andScopesPerUser() = runTest {
        val repo = newRepo()
        val a = newUser()
        val b = newUser()

        repo.record(a, key(1), handNumber = 1, bustsDealt = 1)
        repo.record(a, key(2), handNumber = 2, bustsDealt = 3)
        repo.record(b, key(1), handNumber = 1, bustsDealt = 5)

        assertEquals(4L, repo.bustsDealtForUser(a))
        assertEquals(5L, repo.bustsDealtForUser(b))
    }

    @Test
    fun bustsDealtForUser_isZero_whenNoRows() = runTest {
        assertEquals(0L, newRepo().bustsDealtForUser(newUser()))
    }

    @Test
    fun winsByFoldForUser_countsOnlyFoldWins_andScopesPerUser() = runTest {
        val repo = newRepo()
        val a = newUser()
        val b = newUser()

        repo.record(a, key(1), handNumber = 1, wonByFold = true)
        repo.record(a, key(2), handNumber = 2, wonByFold = false)
        repo.record(a, key(3), handNumber = 3, wonByFold = true)
        repo.record(b, key(1), handNumber = 1, wonByFold = true)

        assertEquals(2L, repo.winsByFoldForUser(a))
        assertEquals(1L, repo.winsByFoldForUser(b))
    }

    @Test
    fun deleteAllForUser_clearsOnlyTheGivenUser() = runTest {
        val repo = newRepo()
        val a = newUser()
        val b = newUser()
        repo.record(a, key(1), handNumber = 1, bustsDealt = 2, wonByFold = true)
        repo.record(b, key(1), handNumber = 1)

        repo.deleteAllForUser(a)

        assertEquals(0L, repo.countForUser(a))
        assertEquals(0L, repo.bustsDealtForUser(a))
        assertEquals(0L, repo.winsByFoldForUser(a))
        assertEquals(1L, repo.countForUser(b))
    }

    private suspend fun PostgresHandsFinishedRepository.record(
        user: UserId,
        idempotencyKey: String,
        session: UUID = UUID.randomUUID(),
        handNumber: Int,
        bustsDealt: Int = 0,
        wonByFold: Boolean = false,
    ) = recordHandFinished(user, idempotencyKey, session, handNumber, bustsDealt, wonByFold)

    private fun key(handNumber: Int): String = "${UUID.randomUUID()}:$handNumber"

    private fun newRepo(clock: Clock = Clock.System): PostgresHandsFinishedRepository =
        PostgresHandsFinishedRepository(database = database, clock = clock)

    /** Mints a fresh UUID + seeds the matching auth.users row for the FK. */
    private fun newUser(): UserId = seedAuthUser()
}
