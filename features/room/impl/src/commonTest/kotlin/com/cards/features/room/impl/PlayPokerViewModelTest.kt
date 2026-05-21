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
import com.dangerfield.cards.libraries.game.ConnectionState
import com.dangerfield.cards.libraries.game.SeatOccupant
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.identity.Identity
import com.dangerfield.cards.libraries.identity.IdentityState
import com.dangerfield.cards.libraries.review.ReviewTrigger
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
        assertEquals(TurnFeedback.Vibrate, state.turnFeedback)
    }

    // ---------- Settings mirror (AppCache → state) ----------

    @Test
    fun appCacheEmission_mirrorsTurnFeedback() = runUnitTest {
        val cache = FakeAppCache()
        val vm = buildVm(appCache = cache)

        cache.emit(AppData(turnFeedback = TurnFeedback.Vibrate))
        assertEquals(TurnFeedback.Vibrate, vm.state.turnFeedback)
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

    // ---------- Connection state mirror (PokerSession → state) ----------

    @Test
    fun sessionConnectionState_mirrorsIntoVmState() = runUnitTest {
        val session = FakePokerSession()
        val factory = FakePokerSessionFactory(session = session)
        val vm = buildVm(factory = factory)

        // Default — local sessions stay Connected.
        assertEquals(ConnectionState.Connected, vm.state.connection)

        session.emitConnectionState(ConnectionState.Reconnecting)
        assertEquals(ConnectionState.Reconnecting, vm.state.connection)

        session.emitConnectionState(ConnectionState.Disconnected)
        assertEquals(ConnectionState.Disconnected, vm.state.connection)

        session.emitConnectionState(ConnectionState.Connected)
        assertEquals(ConnectionState.Connected, vm.state.connection)
    }

    @Test
    fun progressionEmission_mirrorsTotalXp() = runUnitTest {
        val progression = FakeProgressionRepository()
        val vm = buildVm(progressionRepository = progression)

        progression.emit(Progression.Empty.copy(totalXp = 4_200))
        assertEquals(4_200L, vm.state.xp)
    }

    // ---------- Engine state subscription ----------

    @Test
    fun gameStateEmission_populatesTable() = runUnitTest {
        val session = FakePokerSession()
        val factory = FakePokerSessionFactory(session = session)
        val vm = buildVm(factory = factory)

        // The stub state has 2 seats and is on the Preflop street — the
        // derived TableUiState should be Active (not Loading) and reflect that.
        val table = vm.state.table
        assertTrue(table is TableUiState.Active, "table is Active once gameState emits")
        assertEquals(2, (table as TableUiState.Active).seats.size)
    }

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

    // ---------- Identity → human-seat projection ----------

    @Test
    fun signedInIdentity_drivesHumanSeatNameEmojiAndBackgroundColor() = runUnitTest {
        val identity = Identity(
            userId = "u-1",
            displayName = "QuietAce72",
            avatarEmoji = "🦊",
            avatarBackgroundColor = "#58E47C",
            isAnonymous = true,
        )
        val identityRepo = FakeIdentityRepository(
            initial = IdentityState.SignedIn(identity),
        )
        val session = FakePokerSession()
        val factory = FakePokerSessionFactory(session = session)
        val vm = buildVm(factory = factory, identityRepository = identityRepo)

        // Re-emit so the projection runs against the identity captured at init.
        session.emitGameState(
            stubGameState(
                seats = listOf(
                    testSeat(0, "You", isBot = false, playerId = "human"),
                    testSeat(1, "Steve", isBot = true, playerId = "bot-1"),
                ),
            ),
        )

        val table = vm.state.table as TableUiState.Active
        val humanSeat = table.seats.first { it.isHuman }
        assertEquals("QuietAce72", humanSeat.displayName)
        assertEquals("🦊", humanSeat.emoji)
        assertEquals("#58E47C", humanSeat.avatarBackgroundColorHex)
        // Bot seat unaffected — engine name, no identity-driven background.
        val botSeat = table.seats.first { it.isBot }
        assertEquals("Steve", botSeat.displayName)
        assertEquals(null, botSeat.avatarBackgroundColorHex)
    }

    @Test
    fun unknownIdentity_keepsEngineSeatName() = runUnitTest {
        // Default FakeIdentityRepository is Unknown — projection should fall
        // back to the engine seat's own displayName.
        val session = FakePokerSession()
        val factory = FakePokerSessionFactory(session = session)
        val vm = buildVm(factory = factory)

        session.emitGameState(
            stubGameState(
                seats = listOf(
                    testSeat(0, "You", isBot = false, playerId = "human"),
                    testSeat(1, "Steve", isBot = true, playerId = "bot-1"),
                ),
            ),
        )

        val table = vm.state.table as TableUiState.Active
        assertEquals("You", table.seats.first { it.isHuman }.displayName)
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

    @Test
    fun handEnded_countsBustedOpponents_byEndOfHandStack() = runUnitTest {
        val achievements = FakeAchievementRepository()
        val factory = FakePokerSessionFactory()
        buildVm(factory = factory, achievementRepository = achievements)

        // Steve busted (stack 0), Jane survived. Human stack is irrelevant
        // to the count — bots only.
        val state = stubGameState(
            seats = listOf(
                testSeat(0, "You", isBot = false, playerId = "human", stack = 2_500),
                testSeat(1, "Steve", isBot = true, playerId = "bot-1", stack = 0),
                testSeat(2, "Jane", isBot = true, playerId = "bot-2", stack = 800),
            ),
        )
        factory.capturedOnHandEnded?.invoke(
            GameEvent.HandEnded(sequence = 0, winners = emptyList(), board = emptyList(), revealedHoleCards = emptyMap()),
            state,
            /* humanStartingStack = */ 1_500L,
        )

        assertEquals(1, achievements.recordedHands.single().second.bustedOpponentCount)
    }

    @Test
    fun handEnded_zeroBustedOpponents_whenAllSurvive() = runUnitTest {
        val achievements = FakeAchievementRepository()
        val factory = FakePokerSessionFactory()
        buildVm(factory = factory, achievementRepository = achievements)

        val state = stubGameState(
            seats = listOf(
                testSeat(0, "You", isBot = false, playerId = "human", stack = 1_000),
                testSeat(1, "Steve", isBot = true, playerId = "bot-1", stack = 1_000),
                testSeat(2, "Jane", isBot = true, playerId = "bot-2", stack = 1_000),
            ),
        )
        factory.capturedOnHandEnded?.invoke(
            GameEvent.HandEnded(sequence = 0, winners = emptyList(), board = emptyList(), revealedHoleCards = emptyMap()),
            state,
            /* humanStartingStack = */ 1_000L,
        )

        assertEquals(0, achievements.recordedHands.single().second.bustedOpponentCount)
    }

    // ---------- Review prompt wiring ----------

    @Test
    fun handEnded_unlocksRareAchievement_requestsReviewPrompt() = runUnitTest {
        val achievements = FakeAchievementRepository().apply {
            nextEarned = listOf(testEarnedAchievement(rarity = AchievementRarity.RARE))
        }
        val coordinator = FakeReviewPromptCoordinator()
        val factory = FakePokerSessionFactory()
        buildVm(
            factory = factory,
            achievementRepository = achievements,
            reviewPromptCoordinator = coordinator,
        )

        factory.capturedOnHandEnded?.invoke(
            GameEvent.HandEnded(sequence = 0, winners = emptyList(), board = emptyList(), revealedHoleCards = emptyMap()),
            stubGameState(),
            1_000L,
        )

        assertEquals(listOf(ReviewTrigger.AchievementUnlocked), coordinator.requested)
    }

    @Test
    fun handEnded_unlocksLegendaryAchievement_requestsReviewPrompt() = runUnitTest {
        val achievements = FakeAchievementRepository().apply {
            nextEarned = listOf(testEarnedAchievement(rarity = AchievementRarity.LEGENDARY))
        }
        val coordinator = FakeReviewPromptCoordinator()
        val factory = FakePokerSessionFactory()
        buildVm(
            factory = factory,
            achievementRepository = achievements,
            reviewPromptCoordinator = coordinator,
        )

        factory.capturedOnHandEnded?.invoke(
            GameEvent.HandEnded(sequence = 0, winners = emptyList(), board = emptyList(), revealedHoleCards = emptyMap()),
            stubGameState(),
            1_000L,
        )

        assertEquals(listOf(ReviewTrigger.AchievementUnlocked), coordinator.requested)
    }

    @Test
    fun handEnded_unlocksCommonOnly_doesNotRequestAchievementPrompt() = runUnitTest {
        val achievements = FakeAchievementRepository().apply {
            nextEarned = listOf(testEarnedAchievement(rarity = AchievementRarity.COMMON))
        }
        val coordinator = FakeReviewPromptCoordinator()
        val factory = FakePokerSessionFactory()
        buildVm(
            factory = factory,
            achievementRepository = achievements,
            reviewPromptCoordinator = coordinator,
        )

        factory.capturedOnHandEnded?.invoke(
            GameEvent.HandEnded(sequence = 0, winners = emptyList(), board = emptyList(), revealedHoleCards = emptyMap()),
            stubGameState(),
            1_000L,
        )

        assertTrue(
            ReviewTrigger.AchievementUnlocked !in coordinator.requested,
            "common-only earnings must not fire AchievementUnlocked",
        )
    }

    @Test
    fun handEnded_levelChange_requestsLevelUpPrompt() = runUnitTest {
        val progression = FakeProgressionRepository(initial = Progression.Empty.copy(totalXp = 0))
        progression.onAwardForHand = {
            progression.emit(Progression.Empty.copy(totalXp = 150))
        }
        val coordinator = FakeReviewPromptCoordinator()
        val factory = FakePokerSessionFactory()
        buildVm(
            factory = factory,
            progressionRepository = progression,
            reviewPromptCoordinator = coordinator,
        )

        factory.capturedOnHandEnded?.invoke(
            GameEvent.HandEnded(sequence = 0, winners = emptyList(), board = emptyList(), revealedHoleCards = emptyMap()),
            stubGameState(),
            1_000L,
        )

        assertEquals(listOf(ReviewTrigger.LevelUp), coordinator.requested)
    }

    @Test
    fun handEnded_noLevelChange_doesNotRequestLevelUpPrompt() = runUnitTest {
        val progression = FakeProgressionRepository(initial = Progression.Empty.copy(totalXp = 0))
        progression.onAwardForHand = {
            progression.emit(Progression.Empty.copy(totalXp = 30))
        }
        val coordinator = FakeReviewPromptCoordinator()
        val factory = FakePokerSessionFactory()
        buildVm(
            factory = factory,
            progressionRepository = progression,
            reviewPromptCoordinator = coordinator,
        )

        factory.capturedOnHandEnded?.invoke(
            GameEvent.HandEnded(sequence = 0, winners = emptyList(), board = emptyList(), revealedHoleCards = emptyMap()),
            stubGameState(),
            1_000L,
        )

        assertTrue(coordinator.requested.isEmpty(), "no prompts when level doesn't change")
    }

    @Test
    fun handEnded_achievementUnlock_takesPriorityOverLevelUp() = runUnitTest {
        val progression = FakeProgressionRepository(initial = Progression.Empty.copy(totalXp = 0))
        progression.onAwardForHand = {
            progression.emit(Progression.Empty.copy(totalXp = 150))
        }
        val achievements = FakeAchievementRepository().apply {
            nextEarned = listOf(testEarnedAchievement(rarity = AchievementRarity.EPIC))
        }
        val coordinator = FakeReviewPromptCoordinator()
        val factory = FakePokerSessionFactory()
        buildVm(
            factory = factory,
            progressionRepository = progression,
            achievementRepository = achievements,
            reviewPromptCoordinator = coordinator,
        )

        factory.capturedOnHandEnded?.invoke(
            GameEvent.HandEnded(sequence = 0, winners = emptyList(), board = emptyList(), revealedHoleCards = emptyMap()),
            stubGameState(),
            1_000L,
        )

        assertEquals(
            listOf(ReviewTrigger.AchievementUnlocked),
            coordinator.requested,
            "achievement unlock subsumes the level-up trigger",
        )
    }

    // ---------- Helpers ----------

    private fun buildVm(
        factory: PokerSessionFactory = FakePokerSessionFactory(),
        progressionRepository: FakeProgressionRepository = FakeProgressionRepository(),
        achievementRepository: FakeAchievementRepository = FakeAchievementRepository(),
        appCache: FakeAppCache = FakeAppCache(),
        identityRepository: FakeIdentityRepository = FakeIdentityRepository(),
        reviewPromptCoordinator: FakeReviewPromptCoordinator = FakeReviewPromptCoordinator(),
    ): PlayPokerViewModel = PlayPokerViewModel(
        sessionFactory = factory,
        progressionRepository = progressionRepository,
        achievementRepository = achievementRepository,
        appCache = appCache,
        equipmentRepository = FakeEquipmentRepository(),
        identityRepository = identityRepository,
        reviewPromptCoordinator = reviewPromptCoordinator,
        dispatcherProvider = dispatchers,
    )

    private fun testEarnedAchievement(
        rarity: AchievementRarity = AchievementRarity.COMMON,
    ): EarnedAchievement = EarnedAchievement(
        achievement = Achievement(
            id = AchievementId.FIRST_HAND,
            name = "First Hand",
            description = "Play your first hand.",
            icon = "icon",
            rarity = rarity,
            criterion = Criterion.HandsPlayed(target = 1),
            xpReward = 50,
        ),
        earnedAtEpochMs = 0,
    )
}
