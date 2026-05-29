package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CachedInstallIdProviderTest : CoroutineTest() {

    @Test
    fun mintsNewInstallId_andPersistsToAppCache_whenAppCacheValueIsNull() = runUnitTest {
        val cache = FakeAppCache(initial = AppData(installId = null))
        val appScope = AppCoroutineScope(dispatchers)

        val provider = CachedInstallIdProvider(cache, appScope)
        // CoroutineTest's UnconfinedTestDispatcher runs the init {}-launched
        // hydrate eagerly, so by the time current() is called the in-memory
        // ref has been populated and the cache has been updated.
        val minted = provider.current()

        assertNotNull(minted, "Cold-start install must produce an in-memory id")
        assertEquals(minted, cache.get().installId, "Minted id must be persisted to AppCache")
    }

    @Test
    fun reusesPersistedInstallId_whenAppCacheAlreadyHasOne() = runUnitTest {
        val existing = "11111111-1111-1111-1111-111111111111"
        val cache = FakeAppCache(initial = AppData(installId = existing))
        val appScope = AppCoroutineScope(dispatchers)

        val provider = CachedInstallIdProvider(cache, appScope)

        assertEquals(existing, provider.current(), "Hot path keeps the persisted id verbatim")
        assertEquals(existing, cache.get().installId, "Persisted id is not overwritten on read")
    }

    @Test
    fun current_returnsNull_whenAppCacheReadThrows() = runUnitTest {
        // A failing AppCache shouldn't crash the provider — the header
        // simply stays unset and the next request omits it. The server
        // tolerates absence, so transient cache failures don't break /me.
        val cache = ThrowingAppCache()
        val appScope = AppCoroutineScope(dispatchers)

        val provider = CachedInstallIdProvider(cache, appScope)

        assertNull(provider.current(), "Failed hydration leaves current() at null")
    }

    private class FakeAppCache(initial: AppData = AppData()) : AppCache {
        private val state = MutableStateFlow(initial)
        override val updates: Flow<AppData> = state
        override suspend fun get(): AppData = state.value
        override suspend fun set(value: AppData) { state.value = value }
        override suspend fun clear() { state.value = AppData() }
    }

    private class ThrowingAppCache : AppCache {
        override val updates: Flow<AppData> = MutableStateFlow(AppData())
        override suspend fun get(): AppData = error("simulated AppCache failure")
        override suspend fun set(value: AppData) = error("simulated AppCache failure")
        override suspend fun clear() = error("simulated AppCache failure")
    }
}
