package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.isFirstEverSession
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Pins `AppLifecycleTracker` — the single source of truth for
 * `AppData.lastSessionEndedAt`, which the welcome dialog + "you've
 * been gone a while" surfaces read via `AppData.isFirstEverSession()`.
 * Drift here silently breaks first-session gating, so the contract
 * (one onBackground = one cache write at the injected clock's epoch,
 * subsequent backgrounds overwrite) is worth pinning.
 */
class AppLifecycleTrackerTest : CoroutineTest() {

    @Test
    fun onBackground_stampsLastSessionEndedAt_fromInjectedClock() = runUnitTest {
        val cache = FakeAppCache(initial = AppData())
        val tracker = trackerFor(cache, clockEpochMs = FIRST_TICK_EPOCH_MS)

        assertTrue(
            cache.get().isFirstEverSession(),
            "Pre-condition: a fresh install has never recorded a session-end",
        )

        tracker.onBackground(AppEvent.OnBackground)

        assertEquals(FIRST_TICK_EPOCH_MS, cache.get().lastSessionEndedAt)
        assertFalse(
            cache.get().isFirstEverSession(),
            "Recording a session-end flips `isFirstEverSession` off",
        )
    }

    @Test
    fun onBackground_overwritesPreviousValue_onEachInvocation() = runUnitTest {
        // The contract is "last session-end wins", not "first wins" — the
        // welcome dialog uses the value as a recency check, not a cohort
        // tag. Confirm a later background call advances the stamp.
        val cache = FakeAppCache(initial = AppData())
        val mutableClock = MutableClock(FIRST_TICK_EPOCH_MS)
        val tracker = AppLifecycleTracker(
            appCache = cache,
            appScope = AppCoroutineScope(dispatchers),
            clock = mutableClock,
        )

        tracker.onBackground(AppEvent.OnBackground)
        mutableClock.advanceTo(SECOND_TICK_EPOCH_MS)
        tracker.onBackground(AppEvent.OnBackground)

        assertEquals(SECOND_TICK_EPOCH_MS, cache.get().lastSessionEndedAt)
    }

    @Test
    fun onBackground_preservesUnrelatedAppDataFields() = runUnitTest {
        // The write goes through AppCache.update { it.copy(...) }, so every
        // other field on AppData must round-trip untouched. Pin a sentinel
        // unrelated field (`feedbacksGiven`) so a future refactor that
        // accidentally calls `set(AppData(...))` instead of `update {}`
        // gets caught here.
        val cache = FakeAppCache(initial = AppData(feedbacksGiven = 7))
        val tracker = trackerFor(cache, clockEpochMs = FIRST_TICK_EPOCH_MS)

        tracker.onBackground(AppEvent.OnBackground)

        assertEquals(7, cache.get().feedbacksGiven)
        assertEquals(FIRST_TICK_EPOCH_MS, cache.get().lastSessionEndedAt)
    }

    @Test
    fun onForeground_coldBoot_clearsStaleProfileHighlight() = runUnitTest {
        // A pendingProfileHighlight left over from a prior run must not
        // survive a cold boot — otherwise it scrolls a Profile cosmetic
        // shelf to a non-first item on launch.
        val cache = FakeAppCache(initial = AppData(pendingProfileHighlight = "cardback_neon"))
        val tracker = trackerFor(cache, clockEpochMs = FIRST_TICK_EPOCH_MS)

        tracker.onForeground(AppEvent.OnForeground(isColdBoot = true))

        assertEquals(null, cache.get().pendingProfileHighlight)
    }

    @Test
    fun onForeground_warmForeground_leavesProfileHighlightIntact() = runUnitTest {
        // A warm foreground (resume from background) is not a fresh process,
        // so an in-session spotlight signal must be preserved.
        val cache = FakeAppCache(initial = AppData(pendingProfileHighlight = "cardback_neon"))
        val tracker = trackerFor(cache, clockEpochMs = FIRST_TICK_EPOCH_MS)

        tracker.onForeground(AppEvent.OnForeground(isColdBoot = false))

        assertEquals("cardback_neon", cache.get().pendingProfileHighlight)
    }

    private fun trackerFor(cache: AppCache, clockEpochMs: Long): AppLifecycleTracker =
        AppLifecycleTracker(
            appCache = cache,
            appScope = AppCoroutineScope(dispatchers),
            clock = FixedClock(clockEpochMs),
        )

    private class FakeAppCache(initial: AppData = AppData()) : AppCache {
        private val state = MutableStateFlow(initial)
        override val updates: Flow<AppData> = state
        override suspend fun get(): AppData = state.value
        override suspend fun set(value: AppData) { state.value = value }
        override suspend fun clear() { state.value = AppData() }
    }

    private class FixedClock(private val epochMs: Long) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(epochMs)
    }

    private class MutableClock(initialEpochMs: Long) : Clock {
        private var epochMs: Long = initialEpochMs
        fun advanceTo(newEpochMs: Long) { epochMs = newEpochMs }
        override fun now(): Instant = Instant.fromEpochMilliseconds(epochMs)
    }

    private companion object {
        const val FIRST_TICK_EPOCH_MS: Long = 1_700_000_000_000L
        const val SECOND_TICK_EPOCH_MS: Long = 1_700_000_060_000L
    }
}
