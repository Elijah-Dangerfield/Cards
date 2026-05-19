package com.dangerfield.cards.features.progression.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.AchievementId
import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.XpEvent
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.XpSource
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [XpDetailSheetViewModel]'s `combine`-based fan-in. The VM merges
 * three upstream flows (progression, recent XP events, achievement
 * progress) into one state. The interesting cases:
 *  - isLoading flips to false once ALL three flows have emitted at least
 *    once (combine waits for the slowest one).
 *  - A change on any single flow re-emits the combined state.
 */
class XpDetailSheetViewModelTest : CoroutineTest() {

    @Test
    fun initialState_isLoading_beforeAnyFlowEmits() = runUnitTest {
        // combine waits for ALL three upstreams; if any one hasn't emitted
        // the merged state never fires and the VM stays in its initial
        // (loading) shape.
        val vm = XpDetailSheetViewModel(
            progressionRepository = NeverEmittingProgressionRepository,
            xpEventRepository = NeverEmittingXpEventRepository,
            achievementRepository = NeverEmittingAchievementRepository,
        )
        assertEquals(true, vm.state.isLoading)
        assertEquals(Progression.Empty, vm.state.progression)
        assertEquals(AchievementProgress.Empty, vm.state.achievements)
        assertEquals(emptyList(), vm.state.recentEvents)
    }

    @Test
    fun allThreeFlowsEmit_clearsLoading_andPopulatesState() = runUnitTest {
        val seedProgression = Progression.Empty.copy(totalXp = 1_500, handsPlayed = 10)
        val seedEvents = listOf(
            XpEvent(
                id = 1L,
                deltaXp = 100,
                source = XpSource.BASE,
                mode = XpMode.BOTS,
                handId = "hand-1",
                description = null,
                createdAtEpochMs = 1_700_000_000_000L,
            ),
        )
        val seedAchievements = AchievementProgress(
            earned = mapOf(AchievementId.FIRST_HAND to 1_700_000_000_000L),
            counters = mapOf(AchievementId.FIRST_HAND to 1),
            customCounters = emptyMap(),
        )

        val vm = XpDetailSheetViewModel(
            progressionRepository = FakeProgressionRepository(initial = seedProgression),
            xpEventRepository = FakeXpEventRepository(initial = seedEvents),
            achievementRepository = FakeAchievementRepository(initial = seedAchievements),
        )

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.isLoading) last = awaitItem()
            assertEquals(false, last.isLoading)
            assertEquals(seedProgression, last.progression)
            assertEquals(seedEvents, last.recentEvents)
            assertEquals(seedAchievements, last.achievements)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun progressionUpdate_reEmitsCombinedState() = runUnitTest {
        val progression = FakeProgressionRepository(
            initial = Progression.Empty.copy(totalXp = 100L),
        )
        val vm = XpDetailSheetViewModel(
            progressionRepository = progression,
            xpEventRepository = FakeXpEventRepository(),
            achievementRepository = FakeAchievementRepository(),
        )
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.isLoading) last = awaitItem()
            assertEquals(100L, last.progression.totalXp)
            cancelAndIgnoreRemainingEvents()
        }

        // Award a chunk — the combine fan-in must re-fire.
        progression.progression.value = progression.progression.value.copy(totalXp = 500L)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.progression.totalXp != 500L) last = awaitItem()
            assertEquals(500L, last.progression.totalXp)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun newXpEvent_appearsInRecentEvents() = runUnitTest {
        val events = FakeXpEventRepository()
        val vm = XpDetailSheetViewModel(
            progressionRepository = FakeProgressionRepository(),
            xpEventRepository = events,
            achievementRepository = FakeAchievementRepository(),
        )
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.isLoading) last = awaitItem()
            assertEquals(emptyList(), last.recentEvents)
            cancelAndIgnoreRemainingEvents()
        }

        val newEvent = XpEvent(
            id = 42L,
            deltaXp = 250,
            source = XpSource.ACHIEVEMENT,
            mode = XpMode.BOTS,
            handId = null,
            description = "Earned: Ten Hands",
            createdAtEpochMs = 1_700_000_010_000L,
        )
        events.events.value = listOf(newEvent)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.recentEvents.isEmpty()) last = awaitItem()
            assertEquals(listOf(newEvent), last.recentEvents)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Repositories whose Flows never emit — pin the pre-emission state. */
    private object NeverEmittingProgressionRepository :
        com.dangerfield.cards.libraries.cards.ProgressionRepository {
        override fun observeProgression(): kotlinx.coroutines.flow.Flow<Progression> =
            kotlinx.coroutines.flow.flow { /* never emits */ }
        override suspend fun getProgression(): Progression = Progression.Empty
        override suspend fun awardForHand(
            summary: com.dangerfield.cards.libraries.cards.HandResultSummary,
        ): List<XpEvent> = error("not used")
        override suspend fun applyAchievementXp(delta: Int, description: String?): XpEvent =
            error("not used")
        override suspend fun deleteAll() { /* not used */ }
    }

    private object NeverEmittingXpEventRepository :
        com.dangerfield.cards.libraries.cards.XpEventRepository {
        override fun observeRecent(limit: Int): kotlinx.coroutines.flow.Flow<List<XpEvent>> =
            kotlinx.coroutines.flow.flow { /* never emits */ }
        override fun observeSince(sinceEpochMs: Long): kotlinx.coroutines.flow.Flow<List<XpEvent>> =
            kotlinx.coroutines.flow.flow { /* never emits */ }
    }

    private object NeverEmittingAchievementRepository :
        com.dangerfield.cards.libraries.cards.AchievementRepository {
        override fun observeProgress(): kotlinx.coroutines.flow.Flow<AchievementProgress> =
            kotlinx.coroutines.flow.flow { /* never emits */ }
        override suspend fun getProgress(): AchievementProgress = AchievementProgress.Empty
        override suspend fun recordHand(
            summary: com.dangerfield.cards.libraries.cards.HandResultSummary,
            context: com.dangerfield.cards.libraries.cards.AchievementHandContext,
        ): List<com.dangerfield.cards.libraries.cards.EarnedAchievement> = error("not used")
        override suspend fun deleteAll() { /* not used */ }
    }
}
