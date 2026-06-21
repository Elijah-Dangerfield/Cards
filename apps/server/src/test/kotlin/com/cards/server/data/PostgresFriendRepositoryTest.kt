package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.db.FriendRelationsTable
import com.dangerfield.cards.server.domain.RespondResult
import com.dangerfield.cards.server.domain.SendRequestResult
import com.dangerfield.cards.server.domain.UserId
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.deleteAll
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Integration tests for the Postgres-backed friend graph (real Postgres via
 * testcontainers). Covers canonical-pair dedup regardless of direction, the
 * request/accept/decline lifecycle, mutual-request auto-accept, block
 * dominance, and the per-user delete cascade.
 */
@OptIn(ExperimentalTime::class)
class PostgresFriendRepositoryTest : DatabaseTest() {

    @After
    fun cleanTables() {
        database.blockingTransaction { FriendRelationsTable.deleteAll() }
    }

    @Test
    fun sendRequest_createsPendingInbound() = runTest {
        val repo = newRepo()
        val a = newUser()
        val b = newUser()

        assertEquals(SendRequestResult.Sent, repo.sendRequest(a, b))

        assertTrue(repo.listFriends(a).isEmpty())
        assertEquals(listOf(a), repo.listIncomingRequests(b), "b sees a's request")
        assertTrue(repo.listIncomingRequests(a).isEmpty(), "a doesn't see its own outbound")
    }

    @Test
    fun sendRequest_isDirectionIndependent_andDedups() = runTest {
        val repo = newRepo()
        val a = newUser()
        val b = newUser()

        repo.sendRequest(a, b)
        // Same direction again → idempotent.
        assertEquals(SendRequestResult.AlreadyRequested, repo.sendRequest(a, b))
        // Only one row exists for the pair regardless of who reads it.
        assertEquals(1, repo.listIncomingRequests(b).size)
    }

    @Test
    fun mutualRequest_autoAccepts() = runTest {
        val repo = newRepo()
        val a = newUser()
        val b = newUser()

        repo.sendRequest(a, b)
        // b requesting a back completes the friendship.
        assertEquals(SendRequestResult.NowFriends, repo.sendRequest(b, a))

        assertEquals(listOf(b), repo.listFriends(a))
        assertEquals(listOf(a), repo.listFriends(b))
        assertTrue(repo.listIncomingRequests(a).isEmpty())
        assertTrue(repo.listIncomingRequests(b).isEmpty())
    }

    @Test
    fun accept_turnsRequestIntoFriendship() = runTest {
        val repo = newRepo()
        val a = newUser()
        val b = newUser()
        repo.sendRequest(a, b)

        assertEquals(RespondResult.Ok, repo.accept(me = b, other = a))

        assertEquals(listOf(a), repo.listFriends(b))
        assertEquals(listOf(b), repo.listFriends(a))
        assertEquals(SendRequestResult.AlreadyFriends, repo.sendRequest(a, b))
    }

    @Test
    fun accept_ownRequest_isForbidden() = runTest {
        val repo = newRepo()
        val a = newUser()
        val b = newUser()
        repo.sendRequest(a, b)

        assertEquals(RespondResult.Forbidden, repo.accept(me = a, other = b))
    }

    @Test
    fun accept_nonExistent_isNotFound() = runTest {
        val repo = newRepo()
        assertEquals(RespondResult.NotFound, repo.accept(me = newUser(), other = newUser()))
    }

    @Test
    fun decline_removesPendingRequest() = runTest {
        val repo = newRepo()
        val a = newUser()
        val b = newUser()
        repo.sendRequest(a, b)

        assertEquals(RespondResult.Ok, repo.decline(me = b, other = a))

        assertTrue(repo.listIncomingRequests(b).isEmpty())
        // The pair is clear again — a fresh request starts over.
        assertEquals(SendRequestResult.Sent, repo.sendRequest(a, b))
    }

    @Test
    fun decline_acceptedFriendship_isForbidden() = runTest {
        val repo = newRepo()
        val a = newUser()
        val b = newUser()
        repo.sendRequest(a, b)
        repo.accept(me = b, other = a)

        assertEquals(RespondResult.Forbidden, repo.decline(me = b, other = a))
        assertEquals(listOf(a), repo.listFriends(b), "still friends")
    }

    @Test
    fun block_overwritesFriendship_andRejectsFurtherRequests() = runTest {
        val repo = newRepo()
        val a = newUser()
        val b = newUser()
        repo.sendRequest(a, b)
        repo.accept(me = b, other = a)

        repo.block(me = a, other = b)

        assertTrue(repo.listFriends(a).isEmpty(), "block removes the accepted relation")
        assertTrue(repo.listFriends(b).isEmpty())
        assertEquals(SendRequestResult.Blocked, repo.sendRequest(b, a), "blocked pair can't re-request")
        assertEquals(RespondResult.Forbidden, repo.accept(me = b, other = a))
    }

    @Test
    fun listsAreScopedPerUser() = runTest {
        val repo = newRepo()
        val a = newUser()
        val b = newUser()
        val c = newUser()
        repo.sendRequest(a, b)
        repo.sendRequest(c, b)

        assertEquals(setOf(a, c), repo.listIncomingRequests(b).toSet())
        assertTrue(repo.listIncomingRequests(a).isEmpty())
        assertTrue(repo.listIncomingRequests(c).isEmpty())
    }

    @Test
    fun deleteAllForUser_clearsBothSides_andOnlyThatUser() = runTest {
        val repo = newRepo()
        val a = newUser()
        val b = newUser()
        val c = newUser()
        repo.sendRequest(a, b) // a is acted_by (user_a or user_b depending on uuid order)
        repo.sendRequest(c, a) // a is on the other side here
        repo.sendRequest(b, c) // does not involve a

        repo.deleteAllForUser(a)

        assertTrue(repo.listIncomingRequests(a).isEmpty())
        assertTrue(repo.listFriends(a).isEmpty())
        // The b↔c request is untouched.
        assertEquals(listOf(b), repo.listIncomingRequests(c))
    }

    private fun newRepo(): PostgresFriendRepository =
        PostgresFriendRepository(database = database, clock = fixedClock)

    private fun newUser(): UserId = seedAuthUser()

    private val fixedClock: Clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(0)
    }
}
