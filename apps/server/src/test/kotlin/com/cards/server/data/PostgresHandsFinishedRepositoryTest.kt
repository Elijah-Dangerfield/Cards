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
 * Integration tests for the Postgres-backed finished-hand counter (real
 * Postgres via testcontainers), mirroring [PostgresProgressionRepositoryTest].
 * Covers the `(user_id, idempotency_key)` PK dedup (so a replayed
 * hand-completion never double-counts), per-user scoping, and the delete
 * cascade.
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

        repo.recordHandFinished(user, key(handNumber = 1), UUID.randomUUID(), handNumber = 1)
        repo.recordHandFinished(user, key(handNumber = 2), UUID.randomUUID(), handNumber = 2)

        assertEquals(2L, repo.countForUser(user))
    }

    @Test
    fun recordHandFinished_isIdempotentOnKey() = runTest {
        val repo = newRepo()
        val user = newUser()
        val session = UUID.randomUUID()
        val sameKey = "$session:7:${user.value}"

        repo.recordHandFinished(user, sameKey, session, handNumber = 7)
        repo.recordHandFinished(user, sameKey, session, handNumber = 7)

        assertEquals(1L, repo.countForUser(user), "a replayed hand-completion does not double-count")
    }

    @Test
    fun countForUser_isScopedPerUser() = runTest {
        val repo = newRepo()
        val a = newUser()
        val b = newUser()

        repo.recordHandFinished(a, key(1), UUID.randomUUID(), 1)
        repo.recordHandFinished(a, key(2), UUID.randomUUID(), 2)
        repo.recordHandFinished(b, key(1), UUID.randomUUID(), 1)

        assertEquals(2L, repo.countForUser(a))
        assertEquals(1L, repo.countForUser(b))
    }

    @Test
    fun countForUser_isZero_whenNoRows() = runTest {
        assertEquals(0L, newRepo().countForUser(newUser()))
    }

    @Test
    fun deleteAllForUser_clearsOnlyTheGivenUser() = runTest {
        val repo = newRepo()
        val a = newUser()
        val b = newUser()
        repo.recordHandFinished(a, key(1), UUID.randomUUID(), 1)
        repo.recordHandFinished(b, key(1), UUID.randomUUID(), 1)

        repo.deleteAllForUser(a)

        assertEquals(0L, repo.countForUser(a))
        assertEquals(1L, repo.countForUser(b))
    }

    private fun key(handNumber: Int): String = "${UUID.randomUUID()}:$handNumber"

    private fun newRepo(clock: Clock = Clock.System): PostgresHandsFinishedRepository =
        PostgresHandsFinishedRepository(database = database, clock = clock)

    /** Mints a fresh UUID + seeds the matching auth.users row for the FK. */
    private fun newUser(): UserId = seedAuthUser()
}
