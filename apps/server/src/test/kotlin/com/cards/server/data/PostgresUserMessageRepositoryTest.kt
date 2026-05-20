package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.db.UserMessagesTable
import com.dangerfield.cards.server.domain.UserId
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.deleteAll
import org.junit.After
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Integration tests for the Postgres-backed user-messages repo. Hits
 * real Postgres via testcontainers so the unique index on
 * `(user_id, idempotency_key)` and the partial index on unread rows
 * are exercised end-to-end, not just imagined.
 *
 * Tables cleaned in `@After`; class-level container.
 */
@OptIn(ExperimentalTime::class)
class PostgresUserMessageRepositoryTest : DatabaseTest() {

    @After
    fun cleanTables() {
        database.blockingTransaction {
            UserMessagesTable.deleteAll()
        }
    }

    @Test
    fun create_insertsRow_andReturnsItUnacked() = runTest {
        val repo = newRepo()
        val userId = newUser()
        val id = UUID.randomUUID()

        val outcome = repo.create(
            id = id,
            userId = userId,
            idempotencyKey = "k1",
            emoji = "🎉",
            title = "Welcome back",
            body = "We missed you.",
            deepLink = null,
        )

        assertFalse(outcome.wasAlreadyCreated)
        assertEquals(id, outcome.message.id)
        assertEquals(userId, outcome.message.userId)
        assertEquals("🎉", outcome.message.emoji)
        assertEquals("Welcome back", outcome.message.title)
        assertEquals("We missed you.", outcome.message.body)
        assertNull(outcome.message.deepLink)
        assertNull(outcome.message.ackedAt)
    }

    @Test
    fun create_replay_returnsExistingRow_andWasAlreadyCreated() = runTest {
        val repo = newRepo()
        val userId = newUser()
        val first = repo.create(
            id = UUID.randomUUID(),
            userId = userId,
            idempotencyKey = "k1",
            emoji = null,
            title = "Heads up",
            body = "First body.",
            deepLink = null,
        )

        // Different id, different content, same (userId, key) — must
        // collapse to the original row.
        val replay = repo.create(
            id = UUID.randomUUID(),
            userId = userId,
            idempotencyKey = "k1",
            emoji = "🎉",
            title = "DIFFERENT",
            body = "DIFFERENT BODY",
            deepLink = "cards://shop",
        )

        assertTrue(replay.wasAlreadyCreated)
        assertEquals(first.message.id, replay.message.id)
        assertEquals(first.message.title, replay.message.title, "content of the original must win")
        assertEquals(first.message.body, replay.message.body)
        assertNull(replay.message.emoji, "replay must not pick up the new emoji")
    }

    @Test
    fun create_differentUsers_sameKey_areTwoIndependentRows() = runTest {
        val repo = newRepo()
        val alice = newUser()
        val bob = newUser()
        val a = repo.create(
            id = UUID.randomUUID(),
            userId = alice,
            idempotencyKey = "shared-key",
            emoji = null,
            title = "Hi Alice",
            body = "body",
            deepLink = null,
        )
        val b = repo.create(
            id = UUID.randomUUID(),
            userId = bob,
            idempotencyKey = "shared-key",
            emoji = null,
            title = "Hi Bob",
            body = "body",
            deepLink = null,
        )
        assertFalse(a.wasAlreadyCreated)
        assertFalse(b.wasAlreadyCreated)
        assertNotEquals(a.message.id, b.message.id)
    }

    @Test
    fun unreadFor_returnsOnlyUnacked_oldestFirst() = runTest {
        val repo = newRepo(SteppingClock(Instant.parse("2026-01-01T00:00:00Z")))
        val userId = newUser()
        val first = repo.create(
            id = UUID.randomUUID(), userId = userId, idempotencyKey = "a",
            emoji = null, title = "A", body = "a", deepLink = null,
        )
        val second = repo.create(
            id = UUID.randomUUID(), userId = userId, idempotencyKey = "b",
            emoji = null, title = "B", body = "b", deepLink = null,
        )
        val third = repo.create(
            id = UUID.randomUUID(), userId = userId, idempotencyKey = "c",
            emoji = null, title = "C", body = "c", deepLink = null,
        )
        // Ack the middle one — it should drop out of unread.
        repo.ack(userId, second.message.id, Instant.parse("2026-01-02T00:00:00Z"))

        val unread = repo.unreadFor(userId)
        assertEquals(listOf(first.message.id, third.message.id), unread.map { it.id })
    }

    @Test
    fun unreadFor_ignoresOtherUsersMessages() = runTest {
        val repo = newRepo()
        val alice = newUser()
        val bob = newUser()
        repo.create(
            id = UUID.randomUUID(), userId = alice, idempotencyKey = "a1",
            emoji = null, title = "Alice 1", body = "x", deepLink = null,
        )
        repo.create(
            id = UUID.randomUUID(), userId = bob, idempotencyKey = "b1",
            emoji = null, title = "Bob 1", body = "x", deepLink = null,
        )
        assertEquals(1, repo.unreadFor(alice).size)
        assertEquals(1, repo.unreadFor(bob).size)
        assertEquals("Alice 1", repo.unreadFor(alice).single().title)
    }

    @Test
    fun unreadFor_honorsLimit() = runTest {
        val repo = newRepo(SteppingClock(Instant.parse("2026-01-01T00:00:00Z")))
        val userId = newUser()
        repeat(5) { i ->
            repo.create(
                id = UUID.randomUUID(), userId = userId, idempotencyKey = "k$i",
                emoji = null, title = "T$i", body = "b", deepLink = null,
            )
        }
        assertEquals(3, repo.unreadFor(userId, limit = 3).size)
    }

    @Test
    fun ack_flipsRow_andSubsequentAckIsNoOp() = runTest {
        val repo = newRepo()
        val userId = newUser()
        val created = repo.create(
            id = UUID.randomUUID(), userId = userId, idempotencyKey = "k",
            emoji = null, title = "T", body = "b", deepLink = null,
        )
        val ackAt = Instant.parse("2026-02-02T12:00:00Z")
        val flipped = repo.ack(userId, created.message.id, ackAt)
        assertTrue(flipped)

        val flippedAgain = repo.ack(userId, created.message.id, ackAt)
        assertFalse(flippedAgain, "second ack must report no-op")
        assertTrue(repo.unreadFor(userId).isEmpty())
    }

    @Test
    fun ack_returnsFalse_whenIdBelongsToDifferentUser() = runTest {
        val repo = newRepo()
        val alice = newUser()
        val bob = newUser()
        val aliceMsg = repo.create(
            id = UUID.randomUUID(), userId = alice, idempotencyKey = "k",
            emoji = null, title = "T", body = "b", deepLink = null,
        )
        // Bob tries to ack Alice's message — must not succeed.
        assertFalse(repo.ack(bob, aliceMsg.message.id, Instant.parse("2026-01-01T00:00:00Z")))
        assertEquals(1, repo.unreadFor(alice).size, "Alice's message must still be unread")
    }

    @Test
    fun ack_returnsFalse_forUnknownId() = runTest {
        val repo = newRepo()
        assertFalse(
            repo.ack(newUser(), UUID.randomUUID(), Instant.parse("2026-01-01T00:00:00Z")),
        )
    }

    @Test
    fun deleteAllForUser_wipesEverythingForThatUser_only() = runTest {
        val repo = newRepo()
        val alice = newUser()
        val bob = newUser()
        repo.create(
            id = UUID.randomUUID(), userId = alice, idempotencyKey = "a1",
            emoji = null, title = "A", body = "a", deepLink = null,
        )
        repo.create(
            id = UUID.randomUUID(), userId = bob, idempotencyKey = "b1",
            emoji = null, title = "B", body = "b", deepLink = null,
        )

        repo.deleteAllForUser(alice)

        assertTrue(repo.unreadFor(alice).isEmpty())
        assertEquals(1, repo.unreadFor(bob).size, "bob's messages must not be touched")
    }

    @Test
    fun deleteAllForUser_isIdempotent_forUnknownUser() = runTest {
        val repo = newRepo()
        repo.deleteAllForUser(newUser())
    }

    @Test
    fun deepLink_andEmoji_roundTripWhenSet() = runTest {
        val repo = newRepo()
        val userId = newUser()
        val outcome = repo.create(
            id = UUID.randomUUID(), userId = userId, idempotencyKey = "k",
            emoji = "🎁", title = "Gift", body = "5000 chips inbound",
            deepLink = "cards://shop",
        )
        val read = repo.unreadFor(userId).single()
        assertEquals("🎁", read.emoji)
        assertEquals("cards://shop", read.deepLink)
        assertNotNull(outcome.message.createdAt)
    }

    private fun newRepo(clock: Clock = Clock.System): PostgresUserMessageRepository =
        PostgresUserMessageRepository(database = database, clock = clock)

    private fun newUser(): UserId = UserId(UUID.randomUUID())

    /**
     * Hand-cranked clock that advances 1ms per `now()` call so
     * `created_at` is strictly increasing for `ORDER BY created_at ASC`
     * assertions to be deterministic.
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
