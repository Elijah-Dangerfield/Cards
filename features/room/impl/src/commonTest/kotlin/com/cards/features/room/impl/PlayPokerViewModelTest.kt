package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.cards.Achievement
import com.dangerfield.cards.libraries.cards.AchievementId
import com.dangerfield.cards.libraries.cards.AchievementRarity
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.cards.BotSpeed
import com.dangerfield.cards.libraries.cards.Criterion
import com.dangerfield.cards.libraries.cards.EarnedAchievement
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.TurnFeedback
import com.dangerfield.cards.libraries.cards.XpEvent
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.XpSource
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.game.SeatOccupant
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the new [PlayPokerViewModel] — Phase 0.2.f of the MP-readiness refactor.
 *
 * These pin the bot/human-agnostic VM API in isolation, using fakes for the
 * session and repository collaborators. The companion integration suite drives
 * the same VM against a real [LocalBotsSession]; together they cover both the
 * MVI contract and the wiring.
 */
class PlayPokerViewModelTest : CoroutineTest() {

    // ---------- Initial state ----------

    @Test
    fun initialState_isDefault() = runUnitTest {
        val vm = buildVm()
        // Init flows drain on UnconfinedTestDispatcher without explicit advance.
        val state = vm.state
        assertEquals(2, state.occupants.size, "occupants seeded from stub game state")
        assertEquals(false, state.cheatSheetOpen)
        assertEquals(0L, state.xp)
        assertEquals(null, state.lastHandXpAwarded)
        assertTrue(state.recentlyEarned.isEmpty())
        assertEquals(TurnFeedback.Sound, state.turnFeedback)
    }

    // ---------- Settings mirror (AppCache → state) ----------

    @Test
    fun appCacheEmission_mirrorsSkipBustDialog() = runUnitTest {
        val cache = FakeAppCache()
        val vm = buildVm(appCache = cache)
        assertEquals(false, vm.state.skipBustDialog)

        cache.emit(AppData(skipBustDialog = true))
        assertEquals(true, vm.state.skipBustDialog)
    }

    @Test
    fun appCacheEmission_mirrorsTurnFeedbackAndSkipLeave() = runUnitTest {
        val cache = FakeAppCache()
        val vm = buildVm(appCache = cache)

        cache.emit(
            AppData(
                turnFeedback = TurnFeedback.Vibrate,
                skipLeaveBotsConfirm = true,
            ),
        )
        assertEquals(TurnFeedback.Vibrate, vm.state.turnFeedback)
        assertEquals(true, vm.state.skipLeaveBotsConfirm)
    }

    @Test
    fun appCacheBotSpeed_isExposedToSession() = runUnitTest {
        val cache = FakeAppCache()
        val factory = FakePokerSessionFactory()
        val vm = buildVm(appCache = cache, factory = factory)
        vm  // suppress unused — the VM is the system under test, constructed for side effects

        cache.emit(AppData(botSpeed = BotSpeed.Fast))

        assertEquals(BotSpeed.Fast, factory.capturedBotSpeedProvider?.invoke())
    }

    // ---------- XP mirror (ProgressionRepository → state) ----------

    @Test
    fun progressionEmission_mirrorsTotalXp() = runUnitTest {
        val progression = FakeProgressionRepository()
        val vm = buildVm(progressionRepository = progression)

        progression.emit(Progression.Empty.copy(totalXp = 4_200))
        assertEquals(4_200L, vm.state.xp)
    }

    // ---------- Engine state subscription ----------

    @Test
    fun gameStateEmission_populatesOccupants() = runUnitTest {
        val session = FakePokerSession()
        val factory = FakePokerSessionFactory(session = session)
        val vm = buildVm(factory = factory)

        val initial = vm.state.occupants
        assertEquals(2, initial.size)
        assertTrue(initial[0] is SeatOccupant.Human)
        assertTrue(initial[1] is SeatOccupant.Bot)

        session.emitGameState(
            stubGameState(
                seats = listOf(
                    testSeat(0, "You", isBot = false, playerId = "human"),
                    testSeat(1, "Steve", isBot = true, playerId = "bot-1"),
                    testSeat(2, "Jane", isBot = true, playerId = "bot-2"),
                ),
            ),
        )
        assertEquals(3, vm.state.occupants.size)
        assertTrue(vm.state.occupants[2] is SeatOccupant.Bot)
    }

    // ---------- Submit / RequestNextHand → session ----------

    @Test
    fun submit_forwardsIntentToSession() = runUnitTest {
        val session = FakePokerSession()
        val factory = FakePokerSessionFactory(session = session)
        val vm = buildVm(factory = factory)

        vm.takeAction(PlayPokerAction.Submit(PlayerIntent.Fold(seatIndex = 0)))

        assertEquals(1, session.submittedIntents.size)
        assertEquals(PlayerIntent.Fold(seatIndex = 0), session.submittedIntents.first())
    }

    @Test
    fun requestNextHand_signalsSession_andClearsTransients() = runUnitTest {
        val session = FakePokerSession()
        val factory = FakePokerSessionFactory(session = session)
        val vm = buildVm(factory = factory)

        // Pre-seed transient fields by simulating a hand-end on the prior hand.
        vm.takeAction(PlayPokerAction.HandXpAwarded(amount = 73))
        vm.takeAction(
            PlayPokerAction.AchievementsEarned(
                earned = listOf(testEarnedAchievement()),
            ),
        )
        assertEquals(73, vm.state.lastHandXpAwarded)
        assertEquals(1, vm.state.recentlyEarned.size)

        vm.takeAction(PlayPokerAction.RequestNextHand)

        assertEquals(1, session.requestNextHandCount)
        assertEquals(null, vm.state.lastHandXpAwarded)
        assertTrue(vm.state.recentlyEarned.isEmpty())
    }

    // ---------- Local UI actions ----------

    @Test
    fun toggleCheatSheet_flipsState() = runUnitTest {
        val vm = buildVm()
        assertEquals(false, vm.state.cheatSheetOpen)

        vm.takeAction(PlayPokerAction.ToggleCheatSheet)
        assertEquals(true, vm.state.cheatSheetOpen)

        vm.takeAction(PlayPokerAction.ToggleCheatSheet)
        assertEquals(false, vm.state.cheatSheetOpen)
    }

    @Test
    fun dismissEarnedToast_clearsList() = runUnitTest {
        val vm = buildVm()
        vm.takeAction(
            PlayPokerAction.AchievementsEarned(
                earned = listOf(testEarnedAchievement()),
            ),
        )
        assertEquals(1, vm.state.recentlyEarned.size)

        vm.takeAction(PlayPokerAction.DismissEarnedToast)
        assertTrue(vm.state.recentlyEarned.isEmpty())
    }

    // ---------- Settings setters ----------

    @Test
    fun setSkipBustDialog_mutatesCache() = runUnitTest {
        val cache = FakeAppCache()
        val vm = buildVm(appCache = cache)
        assertEquals(false, cache.get().skipBustDialog)

        vm.takeAction(PlayPokerAction.SetSkipBustDialog(value = true))
        assertEquals(true, cache.get().skipBustDialog)
    }

    @Test
    fun setSkipLeaveConfirm_mutatesCache() = runUnitTest {
        val cache = FakeAppCache()
        val vm = buildVm(appCache = cache)

        vm.takeAction(PlayPokerAction.SetSkipLeaveConfirm(value = true))
        assertEquals(true, cache.get().skipLeaveBotsConfirm)
    }

    // ---------- Hand-end callback flow ----------

    @Test
    fun handEnded_awardsXp_andSurfacesAmount() = runUnitTest {
        val progression = FakeProgressionRepository().apply {
            nextAwardedEvents = listOf(
                XpEvent(
                    id = 1,
                    deltaXp = 24,
                    source = XpSource.BASE,
                    mode = XpMode.BOTS,
                    handId = "h-1",
                    createdAtEpochMs = 0,
                ),
                XpEvent(
                    id = 2,
                    deltaXp = 6,
                    source = XpSource.INVESTMENT,
                    mode = XpMode.BOTS,
                    handId = "h-1",
                    createdAtEpochMs = 0,
                ),
            )
        }
        val factory = FakePokerSessionFactory()
        val vm = buildVm(factory = factory, progressionRepository = progression)

        factory.capturedOnHandEnded?.invoke(
            GameEvent.HandEnded(
                sequence = 0,
                winners = emptyList(),
                board = emptyList(),
                revealedHoleCards = emptyMap(),
            ),
            stubGameState(),
            /* humanStartingStack = */ 1_000L,
        )

        assertEquals(1, progression.awardedSummaries.size)
        assertEquals(30, vm.state.lastHandXpAwarded, "24 + 6 from the two XP events")
    }

    @Test
    fun handEnded_recordsAchievement_andSurfacesEarned() = runUnitTest {
        val earnedSample = testEarnedAchievement()
        val achievements = FakeAchievementRepository().apply {
            nextEarned = listOf(earnedSample)
        }
        val factory = FakePokerSessionFactory()
        val vm = buildVm(factory = factory, achievementRepository = achievements)

        factory.capturedOnHandEnded?.invoke(
            GameEvent.HandEnded(sequence = 0, winners = emptyList(), board = emptyList(), revealedHoleCards = emptyMap()),
            stubGameState(),
            1_000L,
        )

        assertEquals(1, achievements.recordedHands.size)
        assertEquals(listOf(earnedSample), vm.state.recentlyEarned)
    }

    @Test
    fun handEnded_passesOpponentBotNames_toAchievementContext() = runUnitTest {
        val achievements = FakeAchievementRepository()
        val factory = FakePokerSessionFactory(difficultyName = "Challenging")
        val vm = buildVm(factory = factory, achievementRepository = achievements)
        vm  // constructed for side effects

        val state = stubGameState(
            seats = listOf(
                testSeat(0, "You", isBot = false, playerId = "human", stack = 1_500),
                testSeat(1, "Steve", isBot = true, playerId = "bot-1"),
                testSeat(2, "Jane", isBot = true, playerId = "bot-2"),
            ),
        )
        factory.capturedOnHandEnded?.invoke(
            GameEvent.HandEnded(sequence = 0, winners = emptyList(), board = emptyList(), revealedHoleCards = emptyMap()),
            state,
            /* humanStartingStack = */ 1_000L,
        )

        val context = achievements.recordedHands.single().second
        assertEquals(listOf("Steve", "Jane"), context.opponentBotNames)
        assertEquals("Challenging", context.botDifficulty)
        assertEquals(1_000L, context.humanStartingStack)
        assertEquals(1_500L, context.humanEndingStack)
        assertEquals(10L, context.bigBlind)
    }

    // ---------- Helpers ----------

    private fun buildVm(
        factory: PokerSessionFactory = FakePokerSessionFactory(),
        progressionRepository: FakeProgressionRepository = FakeProgressionRepository(),
        achievementRepository: FakeAchievementRepository = FakeAchievementRepository(),
        appCache: FakeAppCache = FakeAppCache(),
    ): PlayPokerViewModel = PlayPokerViewModel(
        sessionFactory = factory,
        progressionRepository = progressionRepository,
        achievementRepository = achievementRepository,
        appCache = appCache,
    )

    private fun testEarnedAchievement(): EarnedAchievement = EarnedAchievement(
        achievement = Achievement(
            id = AchievementId.FIRST_HAND,
            name = "First Hand",
            description = "Play your first hand.",
            icon = "icon",
            rarity = AchievementRarity.COMMON,
            criterion = Criterion.HandsPlayed(target = 1),
            xpReward = 50,
        ),
        earnedAtEpochMs = 0,
    )
}
