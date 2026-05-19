package com.dangerfield.cards.features.progression.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.AchievementId
import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [AchievementsViewModel]'s flow plumbing. The VM is a thin "subscribe
 * + map" — the assertions stay on the load-state invariant (isLoading flips
 * to false on the first emission) and the pass-through of progress data.
 */
class AchievementsViewModelTest : CoroutineTest() {

    @Test
    fun initialState_isLoading_andEmpty() = runUnitTest {
        // No emission until the upstream Flow gets a value — the VM
        // ships with isLoading=true so the UI can render a skeleton.
        val achievements = NeverEmittingAchievementRepository()
        val vm = AchievementsViewModel(achievementRepository = achievements)
        assertEquals(true, vm.state.isLoading)
        assertEquals(AchievementProgress.Empty, vm.state.progress)
    }

    @Test
    fun firstEmission_clearsLoading_andSurfacesProgress() = runUnitTest {
        val seedProgress = AchievementProgress(
            earned = mapOf(AchievementId.FIRST_HAND to 1_700_000_000_000L),
            counters = mapOf(AchievementId.FIRST_HAND to 1),
            customCounters = emptyMap(),
        )
        val achievements = FakeAchievementRepository(initial = seedProgress)
        val vm = AchievementsViewModel(achievementRepository = achievements)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.isLoading) last = awaitItem()
            assertEquals(false, last.isLoading)
            assertEquals(seedProgress, last.progress)
            assertTrue(last.progress.isEarned(AchievementId.FIRST_HAND))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun progress_updates_propagateToState() = runUnitTest {
        val achievements = FakeAchievementRepository()
        val vm = AchievementsViewModel(achievementRepository = achievements)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.isLoading) last = awaitItem()
            assertEquals(AchievementProgress.Empty, last.progress)
            cancelAndIgnoreRemainingEvents()
        }

        val updated = AchievementProgress(
            earned = mapOf(AchievementId.HANDS_10 to 1_700_000_001_000L),
            counters = mapOf(AchievementId.HANDS_10 to 10),
            customCounters = mapOf("no_bust_streak" to 4),
        )
        achievements.progress.value = updated

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.progress.counters[AchievementId.HANDS_10] != 10) last = awaitItem()
            assertEquals(updated, last.progress)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** A repository whose Flow never emits — used to pin the initial-state
     *  invariant where `isLoading = true` should hold pre-first-emission. */
    private class NeverEmittingAchievementRepository :
        com.dangerfield.cards.libraries.cards.AchievementRepository {
        override fun observeProgress(): kotlinx.coroutines.flow.Flow<AchievementProgress> =
            kotlinx.coroutines.flow.flow { /* never emits */ }

        override suspend fun getProgress(): AchievementProgress = AchievementProgress.Empty

        override suspend fun recordHand(
            summary: com.dangerfield.cards.libraries.cards.HandResultSummary,
            context: com.dangerfield.cards.libraries.cards.AchievementHandContext,
        ): List<com.dangerfield.cards.libraries.cards.EarnedAchievement> =
            error("recordHand not used by AchievementsViewModel")

        override suspend fun deleteAll() { /* not used */ }
    }
}
