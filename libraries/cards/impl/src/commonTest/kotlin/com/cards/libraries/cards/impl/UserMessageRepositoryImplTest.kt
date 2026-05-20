package com.dangerfield.cards.libraries.cards.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.UserMessage
import com.dangerfield.cards.libraries.cards.UserMessageKind
import com.dangerfield.cards.libraries.cards.storage.db.UserMessageEntity
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map as flowMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Repository-level behavior: the entity ↔ domain mapping, the cache
 * replace semantics (preserving local-only flags across a diff), and
 * the dialog-consume + inbox-mark-shown writes. The Room DAO is faked
 * so the test stays in commonMain; the DAO's own SQL is exercised by
 * Android instrumentation tests when we add them.
 */
@OptIn(ExperimentalTime::class)
class UserMessageRepositoryImplTest : CoroutineTest() {

    private val fixedNow = Instant.parse("2026-03-01T12:00:00Z")
    private val fixedClock = object : Clock {
        override fun now(): Instant = fixedNow
    }

    @Test
    fun consumeNextDialog_returnsHead_andMarksItShown_pendingAck() = runUnitTest {
        val dao = FakeUserMessageDao().apply {
            put(entity("d1", kind = "dialog", createdAt = fixedNow.toEpochMilliseconds() - 10))
            put(entity("d2", kind = "dialog", createdAt = fixedNow.toEpochMilliseconds()))
            put(entity("i1", kind = "inbox", createdAt = fixedNow.toEpochMilliseconds()))
        }
        val repo = UserMessageRepositoryImpl(dao, fixedClock)

        val head = repo.consumeNextDialog()

        assertNotNull(head)
        assertEquals("d1", head.id, "oldest dialog wins")
        val storedHead = dao.byId("d1")!!
        assertEquals(fixedNow.toEpochMilliseconds(), storedHead.shownAtEpochMs)
        assertTrue(storedHead.ackedPending)
    }

    @Test
    fun consumeNextDialog_skipsAlreadyShown_andInboxKind() = runUnitTest {
        val dao = FakeUserMessageDao().apply {
            put(entity("shown", kind = "dialog", shownAt = fixedNow.toEpochMilliseconds() - 1))
            put(entity("inbox-only", kind = "inbox"))
            put(entity("fresh", kind = "dialog", createdAt = fixedNow.toEpochMilliseconds()))
        }
        val repo = UserMessageRepositoryImpl(dao, fixedClock)

        val head = repo.consumeNextDialog()

        assertEquals("fresh", head?.id, "shown rows + inbox rows are skipped")
    }

    @Test
    fun consumeNextDialog_skipsExpired() = runUnitTest {
        val dao = FakeUserMessageDao().apply {
            put(entity("expired", kind = "dialog", expiresAt = fixedNow.toEpochMilliseconds() - 1))
            put(entity("future", kind = "dialog", expiresAt = fixedNow.toEpochMilliseconds() + 1000))
        }
        val repo = UserMessageRepositoryImpl(dao, fixedClock)
        assertEquals("future", repo.consumeNextDialog()?.id)
    }

    @Test
    fun consumeNextDialog_returnsNull_whenQueueIsEmpty() = runUnitTest {
        val repo = UserMessageRepositoryImpl(FakeUserMessageDao(), fixedClock)
        assertNull(repo.consumeNextDialog())
    }

    @Test
    fun markAllInboxShown_flipsUnreadInboxRowsOnly() = runUnitTest {
        val dao = FakeUserMessageDao().apply {
            put(entity("u1", kind = "inbox"))
            put(entity("u2", kind = "inbox"))
            put(entity("u-dialog", kind = "dialog"))
            put(entity("already-shown", kind = "inbox", shownAt = fixedNow.toEpochMilliseconds() - 1))
        }
        val repo = UserMessageRepositoryImpl(dao, fixedClock)

        val count = repo.markAllInboxShown()

        assertEquals(2, count)
        assertNotNull(dao.byId("u1")!!.shownAtEpochMs)
        assertNotNull(dao.byId("u2")!!.shownAtEpochMs)
        assertTrue(dao.byId("u1")!!.ackedPending)
        assertNull(dao.byId("u-dialog")!!.shownAtEpochMs, "dialog kind must not be touched")
    }

    @Test
    fun replaceCache_preservesShownAt_andAckedPending_forSurvivors() = runUnitTest {
        val dao = FakeUserMessageDao().apply {
            // Existing local state — shown + pending-ack, server still has the row.
            put(entity("survives", kind = "inbox", shownAt = 1234L, ackedPending = true))
            put(entity("disappears", kind = "inbox"))
        }
        val repo = UserMessageRepositoryImpl(dao, fixedClock)

        // Server response includes "survives" + a brand-new "new-row".
        repo.replaceCache(listOf(
            message("survives", kind = UserMessageKind.Inbox),
            message("new-row", kind = UserMessageKind.Inbox),
        ))

        assertNull(dao.byId("disappears"), "anything not in the response gets dropped")
        val survivor = dao.byId("survives")!!
        assertEquals(1234L, survivor.shownAtEpochMs, "local shown_at must survive the diff")
        assertTrue(survivor.ackedPending, "pending-ack flag must survive the diff")
        val newRow = dao.byId("new-row")!!
        assertNull(newRow.shownAtEpochMs, "new rows start unshown")
        assertEquals(false, newRow.ackedPending)
    }

    @Test
    fun observeUnreadInboxCount_emitsUpdatedValues() = runUnitTest {
        val dao = FakeUserMessageDao()
        val repo = UserMessageRepositoryImpl(dao, fixedClock)

        repo.observeUnreadInboxCount().test {
            assertEquals(0, awaitItem())
            dao.put(entity("u1", kind = "inbox"))
            assertEquals(1, awaitItem())
            dao.put(entity("u2", kind = "inbox"))
            assertEquals(2, awaitItem())
            // Marking shown flips one out of the count.
            dao.markShown(listOf("u1"), fixedNow.toEpochMilliseconds())
            assertEquals(1, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeInbox_returnsAllInboxRows_newestFirst() = runUnitTest {
        val dao = FakeUserMessageDao().apply {
            put(entity("oldest", kind = "inbox", createdAt = 100))
            put(entity("newest", kind = "inbox", createdAt = 300))
            put(entity("middle", kind = "inbox", createdAt = 200))
            put(entity("dialog", kind = "dialog"))
        }
        val repo = UserMessageRepositoryImpl(dao, fixedClock)
        val list = repo.observeInbox().firstValue()
        assertEquals(listOf("newest", "middle", "oldest"), list.map { it.id })
    }

    @Test
    fun pendingAckIds_returnsOnlyAckedPendingRows() = runUnitTest {
        val dao = FakeUserMessageDao().apply {
            put(entity("not-pending", kind = "dialog"))
            put(entity("pending-a", kind = "dialog", ackedPending = true))
            put(entity("pending-b", kind = "inbox", ackedPending = true))
        }
        val repo = UserMessageRepositoryImpl(dao, fixedClock)
        assertEquals(setOf("pending-a", "pending-b"), repo.pendingAckIds().toSet())
    }

    @Test
    fun mapping_unknownWireKind_fallsBackToDialog() = runUnitTest {
        val dao = FakeUserMessageDao().apply {
            put(entity("future-kind", kind = "banner"))
        }
        val repo = UserMessageRepositoryImpl(dao, fixedClock)
        // Set as a dialog locally despite wire saying "banner" — that's
        // the forward-compat contract.
        repo.replaceCache(listOf(message("future-kind", kind = UserMessageKind.Dialog)))
        // After replaceCache the local row's kind column was overwritten to "dialog".
        assertEquals("dialog", dao.byId("future-kind")!!.kind)
    }

    // ---------- helpers ----------

    private fun entity(
        id: String,
        kind: String,
        createdAt: Long = fixedNow.toEpochMilliseconds(),
        expiresAt: Long? = null,
        shownAt: Long? = null,
        ackedPending: Boolean = false,
    ) = UserMessageEntity(
        id = id,
        kind = kind,
        emoji = null,
        title = "Title $id",
        body = "Body $id",
        deepLink = null,
        createdAtEpochMs = createdAt,
        expiresAtEpochMs = expiresAt,
        shownAtEpochMs = shownAt,
        ackedPending = ackedPending,
    )

    private fun message(
        id: String,
        kind: UserMessageKind,
    ) = UserMessage(
        id = id,
        kind = kind,
        emoji = null,
        title = "Title $id",
        body = "Body $id",
        deepLink = null,
        createdAtEpochMs = 0L,
        expiresAtEpochMs = null,
    )

    private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstValue(): T {
        var value: T? = null
        this.test {
            value = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}

/**
 * In-memory mirror of [UserMessageDao]. The reactive flows back onto a
 * shared MutableStateFlow that emits whenever the map changes — same
 * mental model as Room's `Flow<>` queries, just without Room.
 */
private class FakeUserMessageDao : com.dangerfield.cards.libraries.cards.storage.db.UserMessageDao {
    private val rows = MutableStateFlow<Map<String, UserMessageEntity>>(emptyMap())

    fun put(entity: UserMessageEntity) {
        rows.value = rows.value + (entity.id to entity)
    }
    fun byId(id: String): UserMessageEntity? = rows.value[id]

    override suspend fun upsertAll(messages: List<UserMessageEntity>) {
        rows.value = rows.value + messages.associateBy { it.id }
    }

    override suspend fun getAll(): List<UserMessageEntity> = rows.value.values.toList()

    override suspend fun pendingAckIds(): List<String> =
        rows.value.values.filter { it.ackedPending }.map { it.id }

    override suspend fun replaceCache(messages: List<UserMessageEntity>) {
        val existing = rows.value
        val merged = messages.associate { incoming ->
            val prior = existing[incoming.id]
            incoming.id to incoming.copy(
                shownAtEpochMs = prior?.shownAtEpochMs,
                ackedPending = prior?.ackedPending ?: false,
            )
        }
        rows.value = merged
    }

    override suspend fun consumeNextDialog(nowEpochMs: Long): UserMessageEntity? {
        val head = nextDialog(nowEpochMs) ?: return null
        markShown(listOf(head.id), nowEpochMs)
        return rows.value[head.id]
    }

    override suspend fun nextDialog(nowEpochMs: Long): UserMessageEntity? =
        rows.value.values
            .filter { row ->
                val expiry = row.expiresAtEpochMs
                row.kind == "dialog" &&
                    row.shownAtEpochMs == null &&
                    (expiry == null || expiry > nowEpochMs)
            }
            .minByOrNull { it.createdAtEpochMs }

    override suspend fun markAllUnreadInboxShown(nowEpochMs: Long): Int {
        val targets = rows.value.values.filter { row ->
            val expiry = row.expiresAtEpochMs
            row.kind == "inbox" &&
                row.shownAtEpochMs == null &&
                (expiry == null || expiry > nowEpochMs)
        }
        markShown(targets.map { it.id }, nowEpochMs)
        return targets.size
    }

    override suspend fun markShown(ids: List<String>, nowEpochMs: Long): Int {
        val current = rows.value.toMutableMap()
        ids.forEach { id ->
            current[id]?.let {
                current[id] = it.copy(
                    shownAtEpochMs = nowEpochMs,
                    ackedPending = true,
                )
            }
        }
        rows.value = current
        return ids.count { current.containsKey(it) }
    }

    override fun observeInbox(nowEpochMs: Long): kotlinx.coroutines.flow.Flow<List<UserMessageEntity>> =
        rows.asStateFlow().flowMap { snapshot ->
            snapshot.values
                .filter { row ->
                    val expiry = row.expiresAtEpochMs
                    row.kind == "inbox" && (expiry == null || expiry > nowEpochMs)
                }
                .sortedByDescending { it.createdAtEpochMs }
        }

    override fun observeUnreadInboxCount(nowEpochMs: Long): kotlinx.coroutines.flow.Flow<Int> =
        rows.asStateFlow().flowMap { snapshot ->
            snapshot.values.count { row ->
                val expiry = row.expiresAtEpochMs
                row.kind == "inbox" &&
                    row.shownAtEpochMs == null &&
                    (expiry == null || expiry > nowEpochMs)
            }
        }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}
