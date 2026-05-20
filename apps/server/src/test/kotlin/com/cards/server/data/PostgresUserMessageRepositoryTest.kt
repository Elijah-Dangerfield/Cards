package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.db.UserMessagesTable
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.UserMessageKind
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Integration tests for the Postgres-backed user-messages repo. Hits
 * real Postgres via testcontainers so the V8 + V9 schema (unique index,
 * kind CHECK, expiry filter, partial unread index) is exercised end-to-
 * end. Tables cleaned in `@After`; class-level container.
 */
@OptIn(ExperimentalTime::class)
class PostgresUserMessageRepositoryTest : DatabaseTest() {

    private val fixedNow = Instant.parse("2026-03-01T12:00:00Z")

    @After
    fun cleanTables() {
        database.blockingTransaction {
            UserMessagesTable.deleteAll()
        }
    }

    // ---------- create ----------

    @Test
    fun create_dialog_defaults_unacked_andUnexpired() = runTest {
        val repo = newRepo()
        val userId = newUser()
        val id = UUID.randomUUID()
        val outcome = repo.create(
            id = id,
            userId = userId,
            idempotencyKey = "k1",
            kind = UserMessageKind.Dialog,
            emoji = "🎉",
            title = "Welcome",
            body = "Glad to have you",
            deepLink = null,
            expiresAt = null,
        )
        assertFalse(outcome.wasAlreadyCreated)
        assertEquals(UserMessageKind.Dialog, outcome.message.kind)
        assertNull(outcome.message.expiresAt)
        assertNull(outcome.message.ackedAt)
    }

    @Test
    fun create_inbox_kind_persists() = runTest {
        val repo = newRepo()
        val userId = newUser()
        val outcome = repo.create(
            id = UUID.randomUUID(),
            userId = userId,
            idempotencyKey = "k1",
            kind = UserMessageKind.Inbox,
            emoji = null,
            title = "Maintenance",
            body = "Sunday 9pm UTC",
            deepLink = null,
            expiresAt = null,
        )
        assertEquals(UserMessageKind.Inbox, outcome.message.kind)
    }

    @Test
    fun create_replay_returnsExistingRow_andWasAlreadyCreated() = runTest {
        val repo = newRepo()
        val userId = newUser()
        val first = repo.create(
            id = UUID.randomUUID(), userId = userId, idempotencyKey = "k1",
            kind = UserMessageKind.Dialog, emoji = null,
            title = "Heads up", body = "First body.", deepLink = null, expiresAt = null,
        )
        val replay = repo.create(
            id = UUID.randomUUID(), userId = userId, idempotencyKey = "k1",
            kind = UserMessageKind.Inbox,  // ignored on replay
            emoji = "🎉", title = "DIFFERENT", body = "DIFFERENT BODY",
            deepLink = "cards://shop", expiresAt = fixedNow.plus(1.minutes),
        )
        assertTrue(replay.wasAlreadyCreated)
        assertEquals(first.message.id, replay.message.id)
        assertEquals(UserMessageKind.Dialog, replay.message.kind, "original kind must win")
        assertNull(replay.message.expiresAt, "original expiry must win")
    }

    @Test
    fun create_differentUsers_sameKey_areTwoIndependentRows() = runTest {
        val repo = newRepo()
        val alice = newUser()
        val bob = newUser()
        val a = repo.create(
            id = UUID.randomUUID(), userId = alice, idempotencyKey = "shared",
            kind = UserMessageKind.Dialog, emoji = null,
            title = "Hi Alice", body = "body", deepLink = null, expiresAt = null,
        )
        val b = repo.create(
            id = UUID.randomUUID(), userId = bob, idempotencyKey = "shared",
            kind = UserMessageKind.Dialog, emoji = null,
            title = "Hi Bob", body = "body", deepLink = null, expiresAt = null,
        )
        assertFalse(a.wasAlreadyCreated)
        assertFalse(b.wasAlreadyCreated)
        assertNotEquals(a.message.id, b.message.id)
    }

    // ---------- unreadFor ----------

    @Test
    fun unreadFor_returnsOnlyUnacked_oldestFirst() = runTest {
        val repo = newRepo(SteppingClock(fixedNow))
        val userId = newUser()
        val first = create(repo, userId, "a")
        val second = create(repo, userId, "b")
        val third = create(repo, userId, "c")
        // Ack the middle.
        repo.ackMany(userId, listOf(second.message.id), fixedNow)

        val unread = repo.unreadFor(userId, now = fixedNow.plus(1.minutes))
        assertEquals(listOf(first.message.id, third.message.id), unread.map { it.id })
    }

    @Test
    fun unreadFor_filtersOutExpired() = runTest {
        val repo = newRepo()
        val userId = newUser()
        val stillFresh = create(
            repo, userId, "fresh",
            expiresAt = fixedNow.plus(5.minutes),
        )
        create(
            repo, userId, "stale",
            expiresAt = fixedNow.minus(5.minutes),
        )
        val noExpiry = create(repo, userId, "evergreen", expiresAt = null)

        val unread = repo.unreadFor(userId, now = fixedNow)
        val ids = unread.map { it.id }.toSet()
        assertTrue(stillFresh.message.id in ids)
        assertTrue(noExpiry.message.id in ids)
        assertEquals(2, ids.size, "stale row must be filtered out")
    }

    @Test
    fun unreadFor_ignoresOtherUsersMessages() = runTest {
        val repo = newRepo()
        val alice = newUser()
        val bob = newUser()
        create(repo, alice, "a1")
        create(repo, bob, "b1")
        assertEquals(1, repo.unreadFor(alice, fixedNow).size)
        assertEquals(1, repo.unreadFor(bob, fixedNow).size)
    }

    @Test
    fun unreadFor_honorsLimit() = runTest {
        val repo = newRepo(SteppingClock(fixedNow))
        val userId = newUser()
        repeat(5) { i -> create(repo, userId, "k$i") }
        assertEquals(3, repo.unreadFor(userId, fixedNow, limit = 3).size)
    }

    // ---------- ackMany ----------

    @Test
    fun ackMany_flipsRows_andIsIdempotentOnReplay() = runTest {
        val repo = newRepo(SteppingClock(fixedNow))
        val userId = newUser()
        val a = create(repo, userId, "a")
        val b = create(repo, userId, "b")

        val flipped1 = repo.ackMany(userId, listOf(a.message.id, b.message.id), fixedNow)
        assertEquals(2, flipped1)
        assertTrue(repo.unreadFor(userId, fixedNow).isEmpty())

        // Replay — both already acked, zero rows flipped.
        val flipped2 = repo.ackMany(userId, listOf(a.message.id, b.message.id), fixedNow)
        assertEquals(0, flipped2)
    }

    @Test
    fun ackMany_silentlyIgnoresIdsBelongingToOtherUsers() = runTest {
        val repo = newRepo()
        val alice = newUser()
        val bob = newUser()
        val aliceMsg = create(repo, alice, "a")
        // Bob tries to ack alice's message.
        val flipped = repo.ackMany(bob, listOf(aliceMsg.message.id), fixedNow)
        assertEquals(0, flipped)
        assertEquals(1, repo.unreadFor(alice, fixedNow).size, "alice's message stays unread")
    }

    @Test
    fun ackMany_emptyList_isNoOp() = runTest {
        val repo = newRepo()
        val userId = newUser()
        create(repo, userId, "k")
        assertEquals(0, repo.ackMany(userId, emptyList(), fixedNow))
        assertEquals(1, repo.unreadFor(userId, fixedNow).size)
    }

    @Test
    fun ackMany_mixedKnownAndUnknownIds_acksOnlyKnown() = runTest {
        val repo = newRepo()
        val userId = newUser()
        val real = create(repo, userId, "real")
        val flipped = repo.ackMany(
            userId,
            listOf(real.message.id, UUID.randomUUID(), UUID.randomUUID()),
            fixedNow,
        )
        assertEquals(1, flipped)
    }

    // ---------- sweepExpiredAndAcked ----------

    @Test
    fun sweep_removesAckedAndExpiredButLeavesActive() = runTest {
        val repo = newRepo()
        val alice = newUser()
        val bob = newUser()
        val acked = create(repo, alice, "acked")
        repo.ackMany(alice, listOf(acked.message.id), fixedNow)
        create(repo, alice, "expired", expiresAt = fixedNow.minus(5.minutes))
        val active = create(repo, alice, "active", expiresAt = fixedNow.plus(5.minutes))
        val evergreen = create(repo, bob, "evergreen")

        val result = repo.sweepExpiredAndAcked(fixedNow)

        assertEquals(1, result.ackedPurged)
        assertEquals(1, result.expiredUnackedPurged)
        assertEquals(2, result.total)
        val remaining = repo.unreadFor(alice, fixedNow) + repo.unreadFor(bob, fixedNow)
        assertEquals(setOf(active.message.id, evergreen.message.id), remaining.map { it.id }.toSet())
    }

    @Test
    fun sweep_onEmptyTable_returnsZeroes() = runTest {
        val repo = newRepo()
        val result = repo.sweepExpiredAndAcked(fixedNow)
        assertEquals(0, result.total)
    }

    @Test
    fun sweep_doesNotTouch_unexpiredOrUnacked() = runTest {
        val repo = newRepo()
        val userId = newUser()
        create(repo, userId, "evergreen", expiresAt = null)
        create(repo, userId, "future", expiresAt = fixedNow.plus(5.minutes))
        val result = repo.sweepExpiredAndAcked(fixedNow)
        assertEquals(0, result.total)
        assertEquals(2, repo.unreadFor(userId, fixedNow).size)
    }

    // ---------- deleteAllForUser ----------

    @Test
    fun deleteAllForUser_wipesEverythingForThatUser_only() = runTest {
        val repo = newRepo()
        val alice = newUser()
        val bob = newUser()
        create(repo, alice, "a")
        create(repo, bob, "b")
        repo.deleteAllForUser(alice)
        assertTrue(repo.unreadFor(alice, fixedNow).isEmpty())
        assertEquals(1, repo.unreadFor(bob, fixedNow).size)
    }

    @Test
    fun deleteAllForUser_unknownUser_isNoOp() = runTest {
        newRepo().deleteAllForUser(newUser())
    }

    // ---------- round-trip ----------

    @Test
    fun deepLink_emoji_expiresAt_roundTripWhenSet() = runTest {
        val repo = newRepo()
        val userId = newUser()
        val outcome = repo.create(
            id = UUID.randomUUID(), userId = userId, idempotencyKey = "k",
            kind = UserMessageKind.Inbox,
            emoji = "🎁", title = "Gift", body = "5000 chips inbound",
            deepLink = "cards://shop",
            expiresAt = fixedNow.plus(5.minutes),
        )
        val read = repo.unreadFor(userId, fixedNow).single()
        assertEquals("🎁", read.emoji)
        assertEquals("cards://shop", read.deepLink)
        assertEquals(fixedNow.plus(5.minutes), read.expiresAt)
        assertEquals(UserMessageKind.Inbox, read.kind)
        assertNotNull(outcome.message.createdAt)
    }

    // ---------- helpers ----------

    private suspend fun create(
        repo: PostgresUserMessageRepository,
        userId: UserId,
        key: String,
        kind: UserMessageKind = UserMessageKind.Dialog,
        expiresAt: Instant? = null,
    ) = repo.create(
        id = UUID.randomUUID(),
        userId = userId,
        idempotencyKey = key,
        kind = kind,
        emoji = null,
        title = "T-$key",
        body = "b-$key",
        deepLink = null,
        expiresAt = expiresAt,
    )

    private fun newRepo(clock: Clock = FixedClock(fixedNow)): PostgresUserMessageRepository =
        PostgresUserMessageRepository(database = database, clock = clock)

    private fun newUser(): UserId = UserId(UUID.randomUUID())

    private class FixedClock(private val now: Instant) : Clock {
        override fun now(): Instant = now
    }

    private class SteppingClock(start: Instant) : Clock {
        private var nowInstant = start
        override fun now(): Instant {
            val current = nowInstant
            nowInstant = current.plus(kotlin.time.Duration.parse("1ms"))
            return current
        }
    }
}
