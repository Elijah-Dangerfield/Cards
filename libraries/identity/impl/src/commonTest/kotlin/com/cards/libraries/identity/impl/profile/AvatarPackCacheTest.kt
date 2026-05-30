package com.dangerfield.cards.libraries.identity.impl.profile

import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.profile.AvatarPack
import com.dangerfield.cards.libraries.identity.profile.AvatarPackOutcome
import com.dangerfield.cards.libraries.storage.Cache
import com.dangerfield.cards.libraries.storage.CacheFactory
import com.dangerfield.cards.libraries.storage.CacheJsonSerializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [AvatarPackCache]'s session-aware read/write contract:
 *
 *  - `read()` returns `null` for a freshly-constructed cache (the
 *    `CachedAvatarPack.EMPTY` shape with `packs.isEmpty()` AND
 *    `lastFetchSessionId == null`) — the source of truth that gates
 *    `ProfileRepositoryImpl.fetchAvatarPack`'s "first launch, no
 *    snapshot yet" branch.
 *  - `read()` returns the stored row otherwise, including the
 *    edge case of a successfully-persisted cache with an *empty* packs
 *    list (the catalog is empty server-side but a session id was
 *    recorded) — distinct signal from "never fetched."
 *  - `write()` round-trips through `PackRecord.from` / `toDomain` so
 *    the on-disk shape stays decoupled from the public `AvatarPack`
 *    api type, and a future field addition on `AvatarPack` doesn't
 *    silently break snapshots written by old builds.
 *  - `clear()` resets to `EMPTY` so a subsequent `read()` returns
 *    `null` — the contract sign-out wipes hang their bet on.
 */
class AvatarPackCacheTest : CoroutineTest() {

    @Test
    fun read_freshCache_returnsNull() = runUnitTest {
        val cache = AvatarPackCache(InMemoryCacheFactory)

        assertNull(cache.read(), "Empty + no session id == 'never fetched'")
    }

    @Test
    fun read_afterWrite_returnsStoredRow() = runUnitTest {
        val cache = AvatarPackCache(InMemoryCacheFactory)
        cache.write(
            outcome = AvatarPackOutcome.Success(
                packs = listOf(SAMPLE_PACK),
                palette = listOf("#FFAA00", "#001188"),
            ),
            sessionId = 7L,
            fetchedAtEpochMs = 1_700_000_000L,
        )

        val row = assertNotNull(cache.read())
        assertEquals(1, row.packs.size)
        assertEquals("starter", row.packs.single().id)
        assertEquals(listOf("#FFAA00", "#001188"), row.palette)
        assertEquals(7L, row.lastFetchSessionId)
        assertEquals(1_700_000_000L, row.fetchedAtEpochMs)
    }

    @Test
    fun read_afterEmptyCatalogWrite_stillReturnsRow_notNull() = runUnitTest {
        val cache = AvatarPackCache(InMemoryCacheFactory)
        cache.write(
            outcome = AvatarPackOutcome.Success(packs = emptyList(), palette = emptyList()),
            sessionId = 3L,
            fetchedAtEpochMs = 1L,
        )

        val row = assertNotNull(
            cache.read(),
            "A successful fetch returning an empty catalog is a real signal — read() must not collapse it to null",
        )
        assertTrue(row.packs.isEmpty())
        assertEquals(3L, row.lastFetchSessionId)
    }

    @Test
    fun write_roundTripsAllAvatarPackFields_includingNullUnlockProductId() = runUnitTest {
        val cache = AvatarPackCache(InMemoryCacheFactory)
        val packs = listOf(
            AvatarPack(
                id = "free",
                name = "Free",
                emojis = listOf("🐶", "🐱"),
                unlockProductId = null,
            ),
            AvatarPack(
                id = "premium",
                name = "Premium",
                emojis = listOf("🦊", "🦄"),
                unlockProductId = "avatarpack_premium",
            ),
        )

        cache.write(
            outcome = AvatarPackOutcome.Success(packs = packs, palette = emptyList()),
            sessionId = 1L,
            fetchedAtEpochMs = 0L,
        )

        val roundTripped = assertNotNull(cache.read()).toSuccess().packs
        assertEquals(packs, roundTripped, "PackRecord.from + toDomain must preserve every public field")
    }

    @Test
    fun write_overwritesPreviousSnapshot() = runUnitTest {
        val cache = AvatarPackCache(InMemoryCacheFactory)
        cache.write(
            outcome = AvatarPackOutcome.Success(packs = listOf(SAMPLE_PACK), palette = emptyList()),
            sessionId = 1L,
            fetchedAtEpochMs = 100L,
        )

        cache.write(
            outcome = AvatarPackOutcome.Success(packs = emptyList(), palette = listOf("#000")),
            sessionId = 2L,
            fetchedAtEpochMs = 200L,
        )

        val row = assertNotNull(cache.read())
        assertTrue(row.packs.isEmpty(), "second write replaces packs wholesale, doesn't merge")
        assertEquals(listOf("#000"), row.palette)
        assertEquals(2L, row.lastFetchSessionId)
        assertEquals(200L, row.fetchedAtEpochMs)
    }

    @Test
    fun clear_resetsToEmpty_readReturnsNullAgain() = runUnitTest {
        val cache = AvatarPackCache(InMemoryCacheFactory)
        cache.write(
            outcome = AvatarPackOutcome.Success(packs = listOf(SAMPLE_PACK), palette = emptyList()),
            sessionId = 1L,
            fetchedAtEpochMs = 0L,
        )
        assertNotNull(cache.read(), "precondition: row is present before clear")

        cache.clear()

        assertNull(cache.read(), "clear must reset to EMPTY so subsequent reads look like 'never fetched'")
    }

    @Test
    fun cachedAvatarPack_toSuccess_mirrorsWrittenPalette() = runUnitTest {
        val cache = AvatarPackCache(InMemoryCacheFactory)
        cache.write(
            outcome = AvatarPackOutcome.Success(
                packs = listOf(SAMPLE_PACK),
                palette = listOf("#ABCDEF"),
            ),
            sessionId = 9L,
            fetchedAtEpochMs = 0L,
        )

        val outcome = assertNotNull(cache.read()).toSuccess()

        assertEquals(listOf(SAMPLE_PACK), outcome.packs)
        assertEquals(listOf("#ABCDEF"), outcome.palette)
    }

    // ---------- Scaffolding ----------

    private companion object {
        private val SAMPLE_PACK = AvatarPack(
            id = "starter",
            name = "Starter",
            emojis = listOf("🃏", "🎴", "♣"),
            unlockProductId = null,
        )
    }

    private object InMemoryCacheFactory : CacheFactory {
        override fun <T : Any> inMemory(defaultValue: () -> T): Cache<T> = FakeCache(defaultValue)

        override fun <T : Any> persistent(
            name: String,
            serializer: CacheJsonSerializer<T>,
            loadEagerly: Boolean,
        ): Cache<T> = FakeCache { runBlocking { serializer.read(null) } }
    }

    private class FakeCache<T : Any>(private val initial: () -> T) : Cache<T> {
        private val state = MutableStateFlow(initial())
        override val updates: Flow<T> = state
        override suspend fun get(): T = state.value
        override suspend fun set(value: T) { state.value = value }
        override suspend fun clear() { state.value = initial() }
    }
}
