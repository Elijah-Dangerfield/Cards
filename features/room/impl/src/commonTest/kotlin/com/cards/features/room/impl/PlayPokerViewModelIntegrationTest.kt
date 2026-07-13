package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.features.room.impl.session.LocalBotsSession
import com.dangerfield.cards.features.room.impl.session.PokerSession
import com.dangerfield.cards.features.room.impl.session.PokerSessionFactory
import com.dangerfield.cards.features.room.impl.session.seatToOccupant

import com.dangerfield.cards.libraries.bots.BotDifficulty
import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.libraries.cards.GameSpeed
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.game.SeatOccupant
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.identity.profile.Profile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Integration tests for [PlayPokerViewModel] driving a REAL [LocalBotsSession].**
 *
 * Distinct from [PlayPokerViewModelTest], which uses fake collaborators to pin
 * the VM's MVI contract in isolation. This suite verifies the *wiring* between
 * the VM and a real session — subscription bugs, bot-loop sequencing, hand-end
 * delivery, real engine state propagating to VM state.
 *
 * What this suite does NOT test:
 *  - Game mechanics — [com.dangerfield.cards.libraries.gameplay.GameEngineTest]'s job
 *  - Bot decision quality — the `BotDecision*Test` suite's job
 *  - MVI contract details — covered by [PlayPokerViewModelTest]
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayPokerViewModelIntegrationTest : CoroutineTest() {

    // ---------- Real session wiring ----------

    @Test
    fun initialOccupants_includeHumanAndBots_inHeadsUp() = runUnitTest {
        val (vm, _) = buildVmWithRealSession(seatsCount = 2)
        advanceUntilIdle()

        val occupants = vm.state.occupants
        assertEquals(2, occupants.size)
        assertTrue(occupants.any { it is SeatOccupant.Human }, "human seat present")
        assertTrue(occupants.any { it is SeatOccupant.Bot }, "bot seat present")
    }

    @Test
    fun initialOccupants_haveCorrectSeatIndices() = runUnitTest {
        val (vm, _) = buildVmWithRealSession(seatsCount = 3)
        advanceUntilIdle()

        val occupants = vm.state.occupants
        assertEquals(3, occupants.size)
        assertEquals(setOf(0, 1, 2), occupants.map { it.seatIndex }.toSet())
        assertTrue(occupants[0] is SeatOccupant.Human, "seat 0 is human")
    }

    // ---------- Real game progression ----------

    @Test
    fun submitFold_advancesRealGameEngine_endsHandHeadsUp() = runUnitTest {
        val (vm, session) = buildVmWithRealSession(seatsCount = 2)
        advanceUntilIdle()
        assertEquals(1, session.gameStateFlow.value.handNumber)
        assertEquals(0, session.gameStateFlow.value.actingSeatIndex)

        vm.takeAction(PlayPokerAction.Submit(PlayerIntent.Fold(seatIndex = 0)))
        advanceUntilIdle()

        assertEquals(BettingRound.Complete, session.gameStateFlow.value.street)
    }

    @Test
    fun submitCall_progressesGame_throughBotResponses() = runUnitTest {
        val (vm, session) = buildVmWithRealSession(seatsCount = 2)
        advanceUntilIdle()
        val stateBeforeCall = session.gameStateFlow.value

        vm.takeAction(PlayPokerAction.Submit(PlayerIntent.Call(seatIndex = 0)))
        advanceUntilIdle()

        assertTrue(
            stateBeforeCall != session.gameStateFlow.value,
            "game state must change after the call + bot response",
        )
    }

    // ---------- Real events flow through to the VM ----------

    @Test
    fun engineEvents_areObservedByVm() = runUnitTest {
        val (_, session) = buildVmWithRealSession(seatsCount = 2)
        val events = mutableListOf<GameEvent>()
        launch { session.events.collect { events += it } }
        advanceUntilIdle()

        // Initial hand emits HandStarted + 2 BlindPosted + 2 HoleCardsDealt = 5 events.
        assertTrue(events.size >= 5, "initial hand emits at least 5 events; got ${events.size}")
        assertTrue(events.any { it is GameEvent.HandStarted })
        assertEquals(2, events.filterIsInstance<GameEvent.BlindPosted>().size)
    }

    // ---------- Real hand-end → real achievement context ----------

    @Test
    fun handEnded_firesAchievementContext_withRealOpponentNames() = runUnitTest {
        val (vm, _) = buildVmWithRealSession(
            seatsCount = 3,
            humanSeatIndex = 0,
        )
        advanceUntilIdle()

        vm.takeAction(PlayPokerAction.Submit(PlayerIntent.Fold(seatIndex = 0)))
        advanceUntilIdle()

        assertTrue(
            vm.testProgressionRepository.awardedSummaries.isNotEmpty(),
            "awardForHand was called",
        )

        val achievementContext =
            vm.testAchievementRepository.recordedHands.lastOrNull()?.second
        assertNotNull(achievementContext, "achievement repository's recordHand was called")
        assertEquals(
            listOf("Steve", "Jane"),
            achievementContext.opponentBotNames,
            "real opponent names propagated from the GameState",
        )
        assertEquals("Casual", achievementContext.botDifficulty)
    }

    @Test
    fun handEnded_summaryReflectsRealHandOutcome() = runUnitTest {
        val (vm, _) = buildVmWithRealSession(seatsCount = 2)
        advanceUntilIdle()

        vm.takeAction(PlayPokerAction.Submit(PlayerIntent.Fold(seatIndex = 0)))
        advanceUntilIdle()

        val summary = vm.testProgressionRepository.awardedSummaries.single()
        assertEquals(true, summary.wasFold, "human folded, summary reflects it")
        assertEquals(false, summary.wonPot, "fold means no pot won")
    }

    // ---------- Real next-hand flow ----------

    @Test
    fun requestNextHand_actuallyStartsNewHand_inRealEngine() = runUnitTest {
        val (vm, session) = buildVmWithRealSession(seatsCount = 2)
        advanceUntilIdle()
        assertEquals(1, session.gameStateFlow.value.handNumber)

        vm.takeAction(PlayPokerAction.Submit(PlayerIntent.Fold(seatIndex = 0)))
        advanceUntilIdle()
        vm.takeAction(PlayPokerAction.RequestNextHand)
        advanceUntilIdle()

        assertEquals(2, session.gameStateFlow.value.handNumber)
        assertEquals(BettingRound.Preflop, session.gameStateFlow.value.street)
    }

    @Test
    fun requestNextHand_clearsTransientStateFromPriorHand() = runUnitTest {
        val (vm, _) = buildVmWithRealSession(seatsCount = 2)
        advanceUntilIdle()

        vm.takeAction(PlayPokerAction.HandXpAwarded(amount = 42))
        assertEquals(42, vm.state.lastHandXpAwarded)

        vm.takeAction(PlayPokerAction.RequestNextHand)
        assertEquals(null, vm.state.lastHandXpAwarded)
    }

    // ---------- Settings → real session ----------

    @Test
    fun appCacheGameSpeedChange_isReadByRealSession() = runUnitTest {
        val (vm, _) = buildVmWithRealSession(seatsCount = 2)
        advanceUntilIdle()

        vm.testAppCache.emit(
            com.dangerfield.cards.libraries.cards.AppData(gameSpeed = GameSpeed.Fast),
        )

        assertEquals(GameSpeed.Fast, vm.testFactory.gameSpeedProvider?.invoke())
    }

    // ---------- Helpers ----------

    private fun buildVmWithRealSession(
        seatsCount: Int = 2,
        humanSeatIndex: Int = 0,
        random: Random = Random(42L),
    ): Pair<TestVm, LocalBotsSession> {
        val progression = FakeProgressionRepository()
        val achievements = FakeAchievementRepository()
        val appCache = FakeAppCache()
        val factory = RealLocalSoloFactory(
            random = random,
            seatsCount = seatsCount,
            dispatchers = dispatchers,
        )
        val vm = PlayPokerViewModel(
            sessionFactory = factory,
            progressionRepository = progression,
            playStyleRepository = FakePlayStyleRepository(),
            playerStatsRepository = FakePlayerStatsRepository(),
            progressionConfig = FakeProgressionConfig(),
            achievementRepository = achievements,
            appCache = appCache,
            equipmentRepository = FakeEquipmentRepository(),
            inventoryRepository = FakeInventoryRepository(),
            productsRepository = FakeProductsRepository(),
            chipsRepository = FakeChipsRepository(),
            purchaseChipPack = FakePurchaseChipPackUseCase(),
            profileRepository = FakeProfileRepository(),
            friendRepository = FakeFriendRepository(),
            reportRepository = FakeReportRepository(),
            reviewPromptCoordinator = FakeReviewPromptCoordinator(),
            leaveCashOutNotifier = FakeLeaveCashOutNotifier(),
            dispatcherProvider = dispatchers,
            appScope = com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope(dispatchers),
            clock = kotlin.time.Clock.System,
            socialEnabledConfig = com.dangerfield.cards.libraries.social.SocialEnabled.forTest(enabled = false),
        )
        return TestVm(
            vm = vm,
            testProgressionRepository = progression,
            testAchievementRepository = achievements,
            testAppCache = appCache,
            testFactory = factory,
        ) to factory.builtSession!!
    }

    private class TestVm(
        val vm: PlayPokerViewModel,
        val testProgressionRepository: FakeProgressionRepository,
        val testAchievementRepository: FakeAchievementRepository,
        val testAppCache: FakeAppCache,
        val testFactory: RealLocalSoloFactory,
    ) {
        val state get() = vm.state
        fun takeAction(action: PlayPokerAction) = vm.takeAction(action)
    }

    /**
     * Test-local factory that builds a real [LocalBotsSession]. Mirrors what
     * the production `SoloBotsSessionFactory` will look like once wired in
     * Phase 0.2.g, but lives in the test source set for now to avoid creating
     * production code we'll discard.
     */
    private class RealLocalSoloFactory(
        private val random: Random,
        private val seatsCount: Int,
        private val dispatchers: com.dangerfield.cards.libraries.flowroutines.DispatcherProvider,
        override val difficultyName: String = "Casual",
        override val xpMode: com.dangerfield.cards.libraries.cards.XpMode = com.dangerfield.cards.libraries.cards.XpMode.BOTS,
    ) : PokerSessionFactory {

        private val personalities = listOf(
            BotPersonality.Steve,
            BotPersonality.Jane,
            BotPersonality.David,
        ).take(seatsCount - 1)

        var builtSession: LocalBotsSession? = null
        var gameSpeedProvider: (() -> GameSpeed)? = null

        override fun create(
            humanSeatIndex: Int,
            gameSpeedProvider: () -> GameSpeed,
            onHandEnded: (GameEvent.HandEnded, GameState, Long) -> Unit,
        ): PokerSession {
            this.gameSpeedProvider = gameSpeedProvider
            val session = LocalBotsSession(
                difficulty = BotDifficulty.Casual,
                humanSeatIndex = humanSeatIndex,
                botPersonalities = personalities,
                random = random,
                botActionDelayMs = 0L,
                gameSpeedProvider = gameSpeedProvider,
                onHandEnded = onHandEnded,
                dispatchers = dispatchers,
            )
            builtSession = session
            return session
        }

        override suspend fun bootstrap(session: PokerSession) {
            (session as LocalBotsSession).runUntilHumansTurnOrComplete()
        }

        override fun humanSeatIndex(state: GameState): Int =
            state.seats.firstOrNull { !it.isBot }?.index ?: 0

        override fun occupantsFor(
            state: GameState,
            curve: com.dangerfield.cards.libraries.cards.LevelCurve,
        ): List<SeatOccupant> =
            state.seats.map { seat -> seatToOccupant(seat, personality = null, curve) }

        override fun tableFor(
            state: GameState,
            lastWinners: GameEvent.HandEnded?,
            lastActionBySeat: Map<Int, PlayerAction>,
            humanProfile: Profile.Authenticated?,
            humanLevel: Int?,
            curve: com.dangerfield.cards.libraries.cards.LevelCurve,
        ): TableUiState = TableUiState.fromGameState(
            gameState = state,
            humanSeatIndex = state.seats.firstOrNull { !it.isBot }?.index ?: 0,
            personalitiesBySeat = emptyMap(),
            lastWinners = lastWinners,
            lastActionBySeat = lastActionBySeat,
            humanProfile = humanProfile,
            humanLevel = humanLevel,
            curve = curve,
        )
    }
}
