package com.dangerfield.cards.features.progression.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.AchievementId
import com.dangerfield.cards.libraries.cards.AchievementMode
import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.AllAchievements
import com.dangerfield.cards.libraries.cards.AllAchievementsById
import com.dangerfield.cards.libraries.cards.BUSTS_DEALT
import com.dangerfield.cards.libraries.cards.Criterion
import com.dangerfield.cards.libraries.cards.currentProgress
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

    // ---------- currentProgress helper ----------

    @Test
    fun currentProgress_simpleCriterion_readsFromCounters() {
        val hands500 = AllAchievements.first {
            it.criterion is Criterion.HandsPlayed && it.criterion.target == 500
        }
        val progress = AchievementProgress(
            earned = emptyMap(),
            counters = mapOf(hands500.id to 47),
            customCounters = emptyMap(),
        )
        assertEquals(47, hands500.currentProgress(progress))
    }

    @Test
    fun currentProgress_customCriterion_readsFromCustomCounters() {
        val custom = AllAchievements.first { it.criterion is Criterion.Custom }
        val key = (custom.criterion as Criterion.Custom).key
        val progress = AchievementProgress(
            earned = emptyMap(),
            counters = emptyMap(),
            customCounters = mapOf(key to 3),
        )
        assertEquals(3, custom.currentProgress(progress))
    }

    @Test
    fun currentProgress_missingEntry_isZero() {
        val hands500 = AllAchievements.first {
            it.criterion is Criterion.HandsPlayed && it.criterion.target == 500
        }
        assertEquals(0, hands500.currentProgress(AchievementProgress.Empty))
    }

    @Test
    fun currentProgress_clampsToTarget_whenCounterOverruns() {
        // Once earned the underlying counter often keeps ticking past the
        // threshold. Display should still cap at target so the tile renders
        // "500 / 500", not "847 / 500".
        val hands500 = AllAchievements.first {
            it.criterion is Criterion.HandsPlayed && it.criterion.target == 500
        }
        val progress = AchievementProgress(
            earned = mapOf(hands500.id to 1L),
            counters = mapOf(hands500.id to 847),
            customCounters = emptyMap(),
        )
        assertEquals(500, hands500.currentProgress(progress))
    }

    // ---------- Bust-an-opponent achievement wiring ----------

    @Test
    fun bustAchievements_areRegistered_botMode_withBustsDealtCriterion() {
        val first = AllAchievementsById.getValue(AchievementId.FIRST_BUST_DEALT)
        val fifth = AllAchievementsById.getValue(AchievementId.BUST_DEALT_5)

        // Both criteria read from the same custom-counter key so they tick
        // together as the user busts more opponents.
        assertEquals(BUSTS_DEALT, (first.criterion as Criterion.Custom).key)
        assertEquals(BUSTS_DEALT, (fifth.criterion as Criterion.Custom).key)
        assertEquals(1, first.criterion.target)
        assertEquals(5, fifth.criterion.target)

        // Bot-only for V1; MP variants will get their own ids when MP ships.
        assertEquals(AchievementMode.BOTS, first.mode)
        assertEquals(AchievementMode.BOTS, fifth.mode)
    }

    @Test
    fun bustAchievement_currentProgress_readsFromCustomCounter() {
        val first = AllAchievementsById.getValue(AchievementId.FIRST_BUST_DEALT)
        val progress = AchievementProgress(
            earned = emptyMap(),
            counters = emptyMap(),
            customCounters = mapOf(BUSTS_DEALT to 3),
        )
        // currentProgress clamps at criterion target, so the FIRST achievement
        // (target = 1) reads as 1 even when the underlying counter is 3.
        assertEquals(1, first.currentProgress(progress))
        // Same counter feeds the 5-bust threshold without clamping (3 < 5).
        val fifth = AllAchievementsById.getValue(AchievementId.BUST_DEALT_5)
        assertEquals(3, fifth.currentProgress(progress))
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

        override suspend fun recordTutorialComplete(): com.dangerfield.cards.libraries.cards.EarnedAchievement? =
            error("recordTutorialComplete not used by AchievementsViewModel")

        override suspend fun deleteAll() { /* not used */ }
    }
}
