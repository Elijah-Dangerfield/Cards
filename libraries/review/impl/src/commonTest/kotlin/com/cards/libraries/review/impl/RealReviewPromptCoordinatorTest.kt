package com.dangerfield.cards.libraries.review.impl

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.review.ReviewLauncher
import com.dangerfield.cards.libraries.review.ReviewTrigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Pins [RealReviewPromptCoordinator]'s eligibility gate. Stripped to
 * pure behavior on the AppCache + clock + launcher contract — the
 * platform-side `ReviewLauncher` is exercised via a counting fake so
 * we never reach a real OS call from a unit test.
 */
@OptIn(ExperimentalTime::class)
class RealReviewPromptCoordinatorTest : CoroutineTest() {

    @Test
    fun firstRequest_beforeInstallAgeWindow_doesNotLaunch_butStampsInstallAt() = runUnitTest {
        val clock = MutableClock(at = T0)
        val cache = FakeAppCache()
        val launcher = CountingLauncher()
        val coordinator = RealReviewPromptCoordinator(cache, launcher, clock)

        val launched = coordinator.requestPrompt(ReviewTrigger.AchievementUnlocked)

        assertFalse(launched, "fresh install is below the age floor")
        assertEquals(0, launcher.requestCount)
        assertEquals(T0_MS, cache.get().reviewInstallAt, "first miss stamps install time")
        assertEquals(0L, cache.get().lastReviewPromptAt, "no prompt recorded")
    }

    @Test
    fun secondRequestAfterAgeFloor_launches_andRecordsPrompt() = runUnitTest {
        val clock = MutableClock(at = T0)
        val cache = FakeAppCache()
        val launcher = CountingLauncher()
        val coordinator = RealReviewPromptCoordinator(cache, launcher, clock)

        coordinator.requestPrompt(ReviewTrigger.AchievementUnlocked)
        clock.advance(RealReviewPromptCoordinator.minInstallAge + 1.days)

        val launched = coordinator.requestPrompt(ReviewTrigger.LevelUp)

        assertTrue(launched)
        assertEquals(1, launcher.requestCount)
        assertEquals(clock.nowMs(), cache.get().lastReviewPromptAt)
        assertEquals(T0_MS, cache.get().reviewInstallAt, "installAt preserved across attempts")
    }

    @Test
    fun cooldown_suppressesRepeatRequestsWithinWindow() = runUnitTest {
        val clock = MutableClock(at = T0)
        val cache = FakeAppCache(
            initial = AppData(reviewInstallAt = T0_MS - 10.days.inWholeMilliseconds),
        )
        val launcher = CountingLauncher()
        val coordinator = RealReviewPromptCoordinator(cache, launcher, clock)

        assertTrue(coordinator.requestPrompt(ReviewTrigger.SessionEnd))

        clock.advance(7.days)
        assertFalse(
            coordinator.requestPrompt(ReviewTrigger.AchievementUnlocked),
            "second prompt inside cooldown is suppressed",
        )
        assertEquals(1, launcher.requestCount, "launcher only fired once")
    }

    @Test
    fun cooldown_releasesAfterMinCooldown() = runUnitTest {
        val clock = MutableClock(at = T0)
        val cache = FakeAppCache(
            initial = AppData(reviewInstallAt = T0_MS - 10.days.inWholeMilliseconds),
        )
        val launcher = CountingLauncher()
        val coordinator = RealReviewPromptCoordinator(cache, launcher, clock)

        assertTrue(coordinator.requestPrompt(ReviewTrigger.SessionEnd))

        clock.advance(RealReviewPromptCoordinator.minPromptCooldown + 1.days)

        assertTrue(
            coordinator.requestPrompt(ReviewTrigger.SessionEnd),
            "after cooldown elapses we can prompt again",
        )
        assertEquals(2, launcher.requestCount)
        assertEquals(clock.nowMs(), cache.get().lastReviewPromptAt)
    }

    @Test
    fun concurrentRequests_collapseToOnePrompt() = runUnitTest {
        val clock = MutableClock(at = T0)
        val cache = FakeAppCache(
            initial = AppData(reviewInstallAt = T0_MS - 10.days.inWholeMilliseconds),
        )
        val launcher = CountingLauncher()
        val coordinator = RealReviewPromptCoordinator(cache, launcher, clock)

        val r1 = coordinator.requestPrompt(ReviewTrigger.AchievementUnlocked)
        val r2 = coordinator.requestPrompt(ReviewTrigger.LevelUp)
        val r3 = coordinator.requestPrompt(ReviewTrigger.SessionEnd)

        assertTrue(r1)
        assertFalse(r2, "second concurrent trigger is suppressed by cooldown")
        assertFalse(r3)
        assertEquals(1, launcher.requestCount)
    }

    companion object {
        private val T0: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)
        private const val T0_MS: Long = 1_700_000_000_000
    }
}

@OptIn(ExperimentalTime::class)
private class MutableClock(at: Instant) : Clock {
    private var current: Instant = at
    fun advance(by: Duration) { current += by }
    fun nowMs(): Long = current.toEpochMilliseconds()
    override fun now(): Instant = current
}

private class CountingLauncher : ReviewLauncher {
    var requestCount: Int = 0
        private set
    override suspend fun requestReview() { requestCount++ }
}

private class FakeAppCache(initial: AppData = AppData()) : AppCache {
    private val state = MutableStateFlow(initial)
    override val updates: Flow<AppData> = state
    override suspend fun get(): AppData = state.value
    override suspend fun set(value: AppData) { state.value = value }
    override suspend fun clear() { state.value = AppData() }
}
