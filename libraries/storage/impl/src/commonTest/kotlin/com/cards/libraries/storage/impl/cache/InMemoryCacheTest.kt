package com.dangerfield.cards.libraries.storage.impl.cache

import app.cash.turbine.test
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Pins the contract callers of [InMemoryCache] rely on:
 *  - `initial()` is invoked once on construction, again on every [clear]
 *    (not memoised on the first call) — important for fresh-instance
 *    semantics when callers store mutable wrappers.
 *  - `set` overrides the stored value; later [get] returns it.
 *  - [Cache.update] reads-then-writes via [get] + [set] — the default
 *    impl on the interface, but pinned here so a regression that
 *    overrides [InMemoryCache] without preserving the contract is loud.
 *  - `updates` flow fires on every state transition (initial, set,
 *    clear, update).
 */
class InMemoryCacheTest : CoroutineTest() {

    @Test
    fun construction_seedsWithInitialSupplier() = runUnitTest {
        val cache = InMemoryCache(initial = { "seed" })
        assertEquals("seed", cache.get())
    }

    @Test
    fun set_storesAndExposesNewValue() = runUnitTest {
        val cache = InMemoryCache(initial = { "seed" })
        cache.set("written")
        assertEquals("written", cache.get())
    }

    @Test
    fun clear_recomputesInitialSupplier() = runUnitTest {
        var callCount = 0
        val cache = InMemoryCache(initial = {
            callCount++
            "seed-$callCount"
        })

        assertEquals("seed-1", cache.get())
        cache.set("written")
        assertEquals("written", cache.get())

        cache.clear()

        assertEquals("seed-2", cache.get())
    }

    @Test
    fun clear_returnsFreshInstanceFromSupplier() = runUnitTest {
        val cache = InMemoryCache(initial = { Holder(value = 0) })
        val first = cache.get()
        cache.set(Holder(value = 9))

        cache.clear()

        val second = cache.get()
        assertEquals(0, second.value)
        assertNotSame(first, second, "clear() must recompute via initial(), not reuse the original instance")
    }

    @Test
    fun update_appliesTransformToCurrentValue() = runUnitTest {
        val cache = InMemoryCache(initial = { 1 })
        cache.set(10)

        val returned = cache.update { it + 5 }

        assertEquals(15, returned)
        assertEquals(15, cache.get())
    }

    @Test
    fun updates_flowEmitsInitialAndEverySubsequentWrite() = runUnitTest {
        val cache = InMemoryCache(initial = { "seed" })

        cache.updates.test {
            assertEquals("seed", awaitItem())
            cache.set("first")
            assertEquals("first", awaitItem())
            cache.update { "${it}-twice" }
            assertEquals("first-twice", awaitItem())
            cache.clear()
            assertEquals("seed", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun set_sameValueTwice_doesNotDoubleEmit() = runUnitTest {
        val cache = InMemoryCache(initial = { "seed" })

        cache.updates.test {
            assertEquals("seed", awaitItem())
            cache.set("written")
            assertEquals("written", awaitItem())
            cache.set("written")
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun updates_isReferenceToBackingStateFlow() = runUnitTest {
        val cache = InMemoryCache(initial = { "seed" })
        assertSame(cache.updates, cache.updates, "updates must expose the same flow instance across reads")
    }

    private data class Holder(val value: Int)
}
