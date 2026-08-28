package com.dangerfield.cards.libraries.cards.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.XpSource
import com.dangerfield.cards.libraries.cards.storage.db.XpEventDao
import com.dangerfield.cards.libraries.cards.storage.db.XpEventEntity
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins `XpEventRepositoryImpl` — the projection layer between the Room-backed
 * `xp_events` ledger and the domain `XpEvent`. The repo is small but
 * load-bearing: every Home / Stats / Profile XP-history surface reads through
 * `observeRecent` / `observeSince`, and the enum-parse fallback is what keeps
 * the ledger forward-compatible if a future server build emits an unknown
 * `source` / `mode` string into a row that gets sync'd locally.
 */
class XpEventRepositoryImplTest : CoroutineTest() {

    @Test
    fun observeRecent_mapsEntitiesToDomain() = runUnitTest {
        val dao = FakeDao()
        val repo = XpEventRepositoryImpl(dao)
        dao.emit(
            entity(id = 7, deltaXp = 50, source = "BASE", mode = "BOTS", handId = "h-1"),
            entity(
                id = 8,
                deltaXp = 200,
                source = "ACHIEVEMENT",
                mode = "BOTS",
                handId = null,
                description = "Pocket rockets",
            ),
        )

        repo.observeRecent(limit = 10).test {
            val emitted = awaitItem()
            assertEquals(2, emitted.size)
            val first = emitted[0]
            assertEquals(7L, first.id)
            assertEquals(50, first.deltaXp)
            assertEquals(XpSource.BASE, first.source)
            assertEquals(XpMode.BOTS, first.mode)
            assertEquals("h-1", first.handId)
            val second = emitted[1]
            assertEquals(XpSource.ACHIEVEMENT, second.source)
            assertEquals("Pocket rockets", second.description)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeSince_passesThroughCursorAndMaps() = runUnitTest {
        val dao = FakeDao()
        val repo = XpEventRepositoryImpl(dao)
        dao.emit(entity(id = 1, deltaXp = 10, mode = "MULTIPLAYER"))

        repo.observeSince(sinceEpochMs = 1_000L).test {
            val emitted = awaitItem()
            assertEquals(1, emitted.size)
            assertEquals(XpMode.MULTIPLAYER, emitted.single().mode)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1_000L, dao.lastObserveSinceCursor)
    }

    @Test
    fun unknownSourceString_fallsBackToBase() = runUnitTest {
        // Forward-compat: if a future server build emits an `XpSource` value
        // the client doesn't know about yet (registry drift through a sync),
        // the repo must not crash — fall back to BASE so the row still
        // renders sensibly in the recent-XP feed.
        val dao = FakeDao()
        val repo = XpEventRepositoryImpl(dao)
        dao.emit(entity(source = "NOT_A_REAL_SOURCE"))

        repo.observeRecent(limit = 5).test {
            val event = awaitItem().single()
            assertEquals(XpSource.BASE, event.source)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun unknownModeString_fallsBackToBots() = runUnitTest {
        // Same forward-compat story for mode. BOTS is the safer default —
        // MP credit / league rules gate on `mode == MULTIPLAYER`, so a
        // misparsed row should NOT silently grant MP credit.
        val dao = FakeDao()
        val repo = XpEventRepositoryImpl(dao)
        dao.emit(entity(mode = "MYSTERY_MODE"))

        repo.observeRecent(limit = 5).test {
            val event = awaitItem().single()
            assertEquals(XpMode.BOTS, event.mode)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun allShippingSourcesAndModes_parseCorrectly() = runUnitTest {
        // Defense against the enum-parse path silently swallowing a real
        // value because the symbol gets renamed without grepping for the
        // string serialization. Every shipping enum value must round-trip.
        val dao = FakeDao()
        val repo = XpEventRepositoryImpl(dao)
        val rows = XpSource.entries.flatMap { source ->
            XpMode.entries.map { mode ->
                entity(source = source.name, mode = mode.name, handId = "h-${source.name}")
            }
        }
        dao.emit(*rows.toTypedArray())

        repo.observeRecent(limit = 100).test {
            val mapped = awaitItem()
            assertEquals(XpSource.entries.size * XpMode.entries.size, mapped.size)
            val pairs = mapped.map { it.source to it.mode }.toSet()
            val expected = XpSource.entries.flatMap { s -> XpMode.entries.map { m -> s to m } }.toSet()
            assertEquals(expected, pairs)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun entity(
        id: Long = 1,
        deltaXp: Int = 25,
        source: String = "BASE",
        mode: String = "BOTS",
        handId: String? = null,
        description: String? = null,
        createdAtEpochMs: Long = 0L,
    ): XpEventEntity = XpEventEntity(
        id = id,
        deltaXp = deltaXp,
        source = source,
        mode = mode,
        handId = handId,
        description = description,
        createdAtEpochMs = createdAtEpochMs,
    )

    private class FakeDao : XpEventDao {
        private val flow = MutableStateFlow<List<XpEventEntity>>(emptyList())
        var lastObserveSinceCursor: Long? = null
            private set

        fun emit(vararg rows: XpEventEntity) {
            flow.value = rows.toList()
        }

        override suspend fun insertAll(events: List<XpEventEntity>) {
            flow.value = flow.value + events
        }

        override fun observeRecent(limit: Int): Flow<List<XpEventEntity>> =
            flow.asStateFlow()

        override fun observeSince(sinceEpochMs: Long): Flow<List<XpEventEntity>> {
            lastObserveSinceCursor = sinceEpochMs
            return flow.asStateFlow()
        }

        override suspend fun getUnsynced(limit: Int): List<XpEventEntity> =
            flow.value.filter { !it.synced }.take(limit)

        override suspend fun markSynced(keys: List<String>) {
            val set = keys.toSet()
            flow.value = flow.value.map { if (it.idempotencyKey in set) it.copy(synced = true) else it }
        }

        override suspend fun existingKeys(keys: List<String>): List<String> {
            val set = keys.toSet()
            return flow.value.map { it.idempotencyKey }.filter { it in set }
        }

        override suspend fun deleteAll() {
            flow.value = emptyList()
        }
    }
}
