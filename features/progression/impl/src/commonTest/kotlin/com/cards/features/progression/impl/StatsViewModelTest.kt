package com.dangerfield.cards.features.progression.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.AchievementId
import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.XpBoostStatus
import com.dangerfield.cards.libraries.cards.XpEvent
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.XpSource
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [StatsViewModel]'s `combine`-based fan-in. The VM merges
 * three upstream flows (progression, recent XP events, achievement
 * progress) into one state. The interesting cases:
 *  - isLoading flips to false once ALL three flows have emitted at least
 *    once (combine waits for the slowest one).
 *  - A change on any single flow re-emits the combined state.
 */
class StatsViewModelTest : CoroutineTest() {

    @Test
    fun initialState_isLoading_beforeAnyFlowEmits() = runUnitTest {
        // combine waits for ALL three upstreams; if any one hasn't emitted
        // the merged state never fires and the VM stays in its initial
        // (loading) shape.
        val vm = StatsViewModel(
            progressionRepository = NeverEmittingProgressionRepository,
            playStyleRepository = FakePlayStyleRepository(),
            xpEventRepository = NeverEmittingXpEventRepository,
            achievementRepository = NeverEmittingAchievementRepository,
            playerStatsRepository = FakePlayerStatsRepository(),
            authRepository = FakeAuthRepository(),
            xpBoostRepository = FakeXpBoostRepository(),
            lifetimeStatsRepository = FakeLifetimeStatsRepository(),
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

        val vm = StatsViewModel(
            progressionRepository = FakeProgressionRepository(initial = seedProgression),
            playStyleRepository = FakePlayStyleRepository(),
            xpEventRepository = FakeXpEventRepository(initial = seedEvents),
            achievementRepository = FakeAchievementRepository(initial = seedAchievements),
            playerStatsRepository = FakePlayerStatsRepository(),
            authRepository = FakeAuthRepository(),
            xpBoostRepository = FakeXpBoostRepository(),
            lifetimeStatsRepository = FakeLifetimeStatsRepository(),
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
        val vm = StatsViewModel(
            progressionRepository = progression,
            playStyleRepository = FakePlayStyleRepository(),
            xpEventRepository = FakeXpEventRepository(),
            achievementRepository = FakeAchievementRepository(),
            playerStatsRepository = FakePlayerStatsRepository(),
            authRepository = FakeAuthRepository(),
            xpBoostRepository = FakeXpBoostRepository(),
            lifetimeStatsRepository = FakeLifetimeStatsRepository(),
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
        val vm = StatsViewModel(
            progressionRepository = FakeProgressionRepository(),
            playStyleRepository = FakePlayStyleRepository(),
            xpEventRepository = events,
            achievementRepository = FakeAchievementRepository(),
            playerStatsRepository = FakePlayerStatsRepository(),
            authRepository = FakeAuthRepository(),
            xpBoostRepository = FakeXpBoostRepository(),
            lifetimeStatsRepository = FakeLifetimeStatsRepository(),
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

    @Test
    fun xpBoostEmission_setsExpiryOnState() = runUnitTest {
        val boost = FakeXpBoostRepository()
        val vm = StatsViewModel(
            progressionRepository = FakeProgressionRepository(),
            playStyleRepository = FakePlayStyleRepository(),
            xpEventRepository = FakeXpEventRepository(),
            achievementRepository = FakeAchievementRepository(),
            playerStatsRepository = FakePlayerStatsRepository(),
            authRepository = FakeAuthRepository(),
            xpBoostRepository = boost,
            lifetimeStatsRepository = FakeLifetimeStatsRepository(),
        )
        vm.stateFlow.test {
            var last = awaitItem()
            assertEquals(null, last.xpBoostExpiresAtEpochMs)
            boost.status.value = XpBoostStatus(expiresAtEpochMs = 1_700_000_900_000L)
            while (last.xpBoostExpiresAtEpochMs == null) last = awaitItem()
            assertEquals(1_700_000_900_000L, last.xpBoostExpiresAtEpochMs)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun anonymousAuth_setsIsAnonymous() = runUnitTest {
        val vm = StatsViewModel(
            progressionRepository = FakeProgressionRepository(),
            playStyleRepository = FakePlayStyleRepository(),
            xpEventRepository = FakeXpEventRepository(),
            achievementRepository = FakeAchievementRepository(),
            playerStatsRepository = FakePlayerStatsRepository(),
            authRepository = FakeAuthRepository(initial = anonymousAuthState()),
            xpBoostRepository = FakeXpBoostRepository(),
            lifetimeStatsRepository = FakeLifetimeStatsRepository(),
        )
        vm.stateFlow.test {
            var last = awaitItem()
            while (!last.isAnonymous) last = awaitItem()
            assertEquals(true, last.isAnonymous)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun claimedAuth_keepsIsAnonymousFalse() = runUnitTest {
        val vm = StatsViewModel(
            progressionRepository = FakeProgressionRepository(),
            playStyleRepository = FakePlayStyleRepository(),
            xpEventRepository = FakeXpEventRepository(),
            achievementRepository = FakeAchievementRepository(),
            playerStatsRepository = FakePlayerStatsRepository(),
            authRepository = FakeAuthRepository(initial = claimedAuthState()),
            xpBoostRepository = FakeXpBoostRepository(),
            lifetimeStatsRepository = FakeLifetimeStatsRepository(),
        )
        vm.stateFlow.test {
            assertEquals(false, awaitItem().isAnonymous)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun distinctOpponentsFetch_populatesState() = runUnitTest {
        val vm = StatsViewModel(
            progressionRepository = FakeProgressionRepository(),
            playStyleRepository = FakePlayStyleRepository(),
            xpEventRepository = FakeXpEventRepository(),
            achievementRepository = FakeAchievementRepository(),
            playerStatsRepository = FakePlayerStatsRepository(),
            authRepository = FakeAuthRepository(),
            xpBoostRepository = FakeXpBoostRepository(),
            lifetimeStatsRepository = FakeLifetimeStatsRepository(Result.success(23L)),
        )
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.distinctOpponentsPlayed == null) last = awaitItem()
            assertEquals(23L, last.distinctOpponentsPlayed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun distinctOpponentsFetchFailure_leavesCountNull() = runUnitTest {
        val vm = StatsViewModel(
            progressionRepository = FakeProgressionRepository(),
            playStyleRepository = FakePlayStyleRepository(),
            xpEventRepository = FakeXpEventRepository(),
            achievementRepository = FakeAchievementRepository(),
            playerStatsRepository = FakePlayerStatsRepository(),
            authRepository = FakeAuthRepository(),
            xpBoostRepository = FakeXpBoostRepository(),
            lifetimeStatsRepository = FakeLifetimeStatsRepository(
                Result.failure(IllegalStateException("offline")),
            ),
        )
        assertEquals(null, vm.state.distinctOpponentsPlayed)
    }

    @Test
    fun playerStatsEmission_populatesNoBustStreak() = runUnitTest {
        val playerStats = FakePlayerStatsRepository()
        val vm = StatsViewModel(
            progressionRepository = FakeProgressionRepository(),
            playStyleRepository = FakePlayStyleRepository(),
            xpEventRepository = FakeXpEventRepository(),
            achievementRepository = FakeAchievementRepository(),
            playerStatsRepository = playerStats,
            authRepository = FakeAuthRepository(),
            xpBoostRepository = FakeXpBoostRepository(),
            lifetimeStatsRepository = FakeLifetimeStatsRepository(),
        )
        vm.stateFlow.test {
            assertEquals(null, awaitItem().playerStats)
            playerStats.stats.value = com.dangerfield.cards.libraries.cards.PlayerStats.Empty.copy(
                currentNoBustStreak = 7,
                bestNoBustStreak = 19,
            )
            var stats = awaitItem().playerStats
            while (stats == null) stats = awaitItem().playerStats
            assertEquals(7L, stats.currentNoBustStreak)
            assertEquals(19L, stats.bestNoBustStreak)
            cancelAndIgnoreRemainingEvents()
        }
    }

}
