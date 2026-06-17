package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.cards.XP_BOOST_DEFAULT_DURATION_MS
import com.dangerfield.cards.libraries.cards.XP_BOOST_MULTIPLIER
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

class XpBoostRepositoryImplTest : CoroutineTest() {

    @Test
    fun grant_addsToStash_withoutOpeningWindow() = runUnitTest {
        val cache = FakeAppCache()
        val repo = XpBoostRepositoryImpl(appCache = cache, clock = MutableClock(now = 1_000L))

        repo.grant()
        repo.grant(count = 2)

        assertEquals(3, cache.get().xpBoostOwnedCount, "stash accumulates")
        assertEquals(null, cache.get().xpBoostExpiresAtEpochMs, "granting never lights a boost")
    }

    @Test
    fun activate_withEmptyStash_isNoOp() = runUnitTest {
        val cache = FakeAppCache()
        val repo = XpBoostRepositoryImpl(appCache = cache, clock = MutableClock(now = 1_000L))

        val activated = repo.activate()

        assertEquals(false, activated, "nothing to light")
        assertEquals(null, cache.get().xpBoostExpiresAtEpochMs)
        assertEquals(0, cache.get().xpBoostOwnedCount)
    }

    @Test
    fun activate_fromInactive_consumesOne_andSetsWindowNowPlusDuration() = runUnitTest {
        val cache = FakeAppCache()
        val repo = XpBoostRepositoryImpl(appCache = cache, clock = MutableClock(now = 1_000L))
        repo.grant()

        val activated = repo.activate()

        assertEquals(true, activated)
        assertEquals(1_000L + XP_BOOST_DEFAULT_DURATION_MS, cache.get().xpBoostExpiresAtEpochMs)
        assertEquals(0, cache.get().xpBoostOwnedCount, "the lit boost left the stash")
    }

    @Test
    fun activate_whileActive_extendsFromCurrentExpiry_andConsumesAnother() = runUnitTest {
        val clock = MutableClock(now = 1_000L)
        val cache = FakeAppCache()
        val repo = XpBoostRepositoryImpl(appCache = cache, clock = clock)
        repo.grant(count = 2)

        repo.activate(durationMs = 10_000L) // expires at 11_000
        clock.now = 5_000L                   // still active
        repo.activate(durationMs = 10_000L) // stacks → 21_000

        assertEquals(21_000L, cache.get().xpBoostExpiresAtEpochMs)
        assertEquals(0, cache.get().xpBoostOwnedCount)
    }

    @Test
    fun activate_afterExpiry_startsFreshFromNow() = runUnitTest {
        val clock = MutableClock(now = 1_000L)
        val cache = FakeAppCache()
        val repo = XpBoostRepositoryImpl(appCache = cache, clock = clock)
        repo.grant(count = 2)

        repo.activate(durationMs = 10_000L) // expires at 11_000
        clock.now = 50_000L                  // lapsed
        repo.activate(durationMs = 10_000L) // fresh → 60_000

        assertEquals(60_000L, cache.get().xpBoostExpiresAtEpochMs)
    }

    @Test
    fun multiplier_isBoostedWhileActive_andOneWhenLapsed() = runUnitTest {
        val clock = MutableClock(now = 1_000L)
        val cache = FakeAppCache()
        val repo = XpBoostRepositoryImpl(appCache = cache, clock = clock)
        repo.grant()

        repo.activate(durationMs = 10_000L)
        assertEquals(XP_BOOST_MULTIPLIER, repo.multiplier())

        clock.now = 11_001L
        assertEquals(1, repo.multiplier())
    }

    @Test
    fun multiplier_isOne_whenNeverActivated() = runUnitTest {
        val repo = XpBoostRepositoryImpl(appCache = FakeAppCache(), clock = MutableClock(now = 1_000L))
        assertEquals(1, repo.multiplier())
    }

    private class MutableClock(var now: Long) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(now)
    }

    private class FakeAppCache(initial: AppData = AppData()) : AppCache {
        private val state = MutableStateFlow(initial)
        override val updates: Flow<AppData> = state
        override suspend fun get(): AppData = state.value
        override suspend fun set(value: AppData) { state.value = value }
        override suspend fun clear() { state.value = AppData() }
    }
}
