package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.bots.BotDifficulty
import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [LocalBotsSession] — the orchestration layer that owns the
 * bot loop, the GameEngine state, and the hand-end callback.
 *
 * The session is wired through [dispatchers] (a [TestDispatcherProvider]) so
 * its `withContext(dispatchers.default)` Monte Carlo work runs on the test
 * scheduler — no real worker threads, no need for `advanceUntilIdle` to fight
 * with real time.
 *
 * The bot loop suspends on `nextHandSignal.receive()` after a hand ends;
 * [CoroutineTest.runUnitTest] cancels it cleanly at test exit so we don't
 * fight `UncompletedCoroutinesError`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalBotsSessionTest : CoroutineTest() {

    // ---------- Initial state ----------

    @Test
    fun headsUp_humanIsActingFirst_afterBlinds() = runUnitTest {
        val session = buildSession(seatsCount = 2)
        launch { session.runUntilHumansTurnOrComplete() }
        runCurrent()

        // Heads-up: button posts SB and acts first preflop. Human at seat 0 is button.
        assertEquals(0, session.gameStateFlow.value.actingSeatIndex)
        assertEquals(BettingRound.Preflop, session.gameStateFlow.value.street)
    }

    @Test
    fun headsUp_blindsArePosted() = runUnitTest {
        val session = buildSession(seatsCount = 2)
        launch { session.runUntilHumansTurnOrComplete() }
        runCurrent()

        val state = session.gameStateFlow.value
        assertEquals(5L, state.seatAt(0).contributedThisStreet, "SB from button (heads-up)")
        assertEquals(10L, state.seatAt(1).contributedThisStreet, "BB from non-button")
        assertEquals(10L, state.currentBetThisStreet)
    }

    @Test
    fun threeHanded_botLoopAdvancesPast_botTurns() = runUnitTest {
        // Human at seat 2; bots at seats 0 and 1. Bots act first preflop.
        val session = buildSession(seatsCount = 3, humanSeatIndex = 2)
        launch { session.runUntilHumansTurnOrComplete() }
        advanceUntilIdle()

        // After the bot loop, either we've reached the human's turn, or the
        // hand finished (rare with seed 42 but possible). Either way, bots
        // must have actually acted.
        val state = session.gameStateFlow.value
        val acting = state.actingSeatIndex
        val handComplete = state.street == BettingRound.Complete
        assertTrue(
            acting == 2 || handComplete,
            "loop yields on human turn (2) or hand complete; got acting=$acting street=${state.street}",
        )
    }

    @Test
    fun handStartedAndBlindPostedEvents_emitted_onConstruction() = runUnitTest {
        val session = buildSession(seatsCount = 2)
        val collected = mutableListOf<GameEvent>()
        launch { session.events.collect { collected += it } }
        launch { session.runUntilHumansTurnOrComplete() }
        runCurrent()

        assertTrue(
            collected.any { it is GameEvent.HandStarted },
            "HandStarted emits on session construction (replay buffer covers late subscribers)",
        )
        assertEquals(
            2,
            collected.filterIsInstance<GameEvent.BlindPosted>().size,
            "two blinds posted heads-up",
        )
    }

    // ---------- Human action handling ----------

    @Test
    fun humanFold_endsHandHeadsUp_andEmitsHandEnded() = runUnitTest {
        val session = buildSession(seatsCount = 2)
        launch { session.runUntilHumansTurnOrComplete() }
        runCurrent()
        assertEquals(0, session.gameStateFlow.value.actingSeatIndex)

        val handEndedEvents = mutableListOf<GameEvent.HandEnded>()
        launch {
            session.events.collect { if (it is GameEvent.HandEnded) handEndedEvents += it }
        }
        // `submitHumanIntent` internally calls `runUntilHumansTurnOrComplete`,
        // which suspends on the next-hand signal once the hand ends. Launch it
        // so the test body doesn't hang at that suspension point.
        launch { session.submitHumanIntent(PlayerIntent.Fold(seatIndex = 0)) }
        advanceUntilIdle()

        assertEquals(BettingRound.Complete, session.gameStateFlow.value.street)
        assertEquals(1, handEndedEvents.size, "exactly one HandEnded event")
        assertTrue(handEndedEvents.first().winners.first().byFold)
    }

    @Test
    fun submittingIntentNotOnHumansTurn_isSilentlyDropped() = runUnitTest {
        // 3-handed: human at seat 2 (acts last preflop after BB). Submitting fold
        // when a bot is acting should be a no-op.
        val session = buildSession(seatsCount = 3, humanSeatIndex = 2)
        launch { session.runUntilHumansTurnOrComplete() }
        runCurrent()

        val stateBefore = session.gameStateFlow.value
        session.submitHumanIntent(PlayerIntent.Fold(seatIndex = 2))
        runCurrent()

        assertEquals(stateBefore, session.gameStateFlow.value)
    }

    @Test
    fun submittingIllegalCheck_facingBet_isSilentlyDropped() = runUnitTest {
        val session = buildSession(seatsCount = 2)
        launch { session.runUntilHumansTurnOrComplete() }
        runCurrent()

        // Heads-up preflop: human is button + SB (contributed 5). BB is 10. Can't check.
        val stateBefore = session.gameStateFlow.value
        session.submitHumanIntent(PlayerIntent.Check(seatIndex = 0))
        runCurrent()

        assertEquals(stateBefore, session.gameStateFlow.value)
    }

    @Test
    fun humanCall_progressesGameState() = runUnitTest {
        val session = buildSession(seatsCount = 2)
        launch { session.runUntilHumansTurnOrComplete() }
        runCurrent()
        val before = session.gameStateFlow.value

        session.submitHumanIntent(PlayerIntent.Call(seatIndex = 0))
        advanceUntilIdle()

        // The call definitely changed something — either we advanced to flop
        // (bot checked) or to a higher-bet preflop (bot raised) or the hand
        // ended. Game state must differ from pre-call.
        assertTrue(
            before != session.gameStateFlow.value,
            "human Call must mutate game state through the engine",
        )
    }

    // ---------- Bot loop ----------

    @Test
    fun botLoop_emitsActionTakenEvents_forBotTurns() = runUnitTest {
        val session = buildSession(seatsCount = 3, humanSeatIndex = 2)
        val actions = mutableListOf<GameEvent.ActionTaken>()
        launch {
            session.events.collect { if (it is GameEvent.ActionTaken) actions += it }
        }
        launch { session.runUntilHumansTurnOrComplete() }
        advanceUntilIdle()

        val botActors = actions.map { it.seatIndex }.toSet()
        assertTrue(0 in botActors || 1 in botActors, "at least one bot acted")
        assertTrue(2 !in botActors, "human at seat 2 has NOT acted yet")
    }

    // ---------- Hand-end callback ----------

    @Test
    fun handEndedCallback_firesWithHandEndedEvent() = runUnitTest {
        var capturedEvent: GameEvent.HandEnded? = null
        var capturedState: GameState? = null
        var capturedStartStack: Long? = null
        val session = buildSession(
            seatsCount = 2,
            onHandEnded = { event, state, startStack ->
                capturedEvent = event
                capturedState = state
                capturedStartStack = startStack
            },
        )
        launch { session.runUntilHumansTurnOrComplete() }
        runCurrent()

        // Launch because submitHumanIntent suspends on the next-hand signal
        // after the fold completes the hand.
        launch { session.submitHumanIntent(PlayerIntent.Fold(seatIndex = 0)) }
        advanceUntilIdle()

        assertNotNull(capturedEvent, "callback fired")
        assertEquals(BettingRound.Complete, capturedState!!.street)
        assertNotNull(capturedStartStack)
    }

    @Test
    fun handEndedCallback_capturesHumanStartStack_postBlind() = runUnitTest {
        var capturedStartStack: Long? = null
        val session = buildSession(
            seatsCount = 2,
            onHandEnded = { _, _, s -> capturedStartStack = s },
        )
        launch { session.runUntilHumansTurnOrComplete() }
        runCurrent()

        launch { session.submitHumanIntent(PlayerIntent.Fold(seatIndex = 0)) }
        advanceUntilIdle()

        // Human posted SB (5 chips) at hand start. The "starting stack" capture
        // reflects post-blind state.
        assertEquals(995L, capturedStartStack, "starting stack of 1000 - 5 SB = 995")
    }

    // ---------- Next hand ----------

    @Test
    fun advanceToNextHand_afterHandEnd_startsNewHand_incrementsHandNumber() = runUnitTest {
        val session = buildSession(seatsCount = 2)
        launch { session.runUntilHumansTurnOrComplete() }
        runCurrent()
        assertEquals(1, session.gameStateFlow.value.handNumber)

        launch { session.submitHumanIntent(PlayerIntent.Fold(seatIndex = 0)) }
        advanceUntilIdle()
        assertEquals(BettingRound.Complete, session.gameStateFlow.value.street)

        session.advanceToNextHand()
        advanceUntilIdle()

        assertEquals(2, session.gameStateFlow.value.handNumber)
        assertEquals(
            BettingRound.Preflop,
            session.gameStateFlow.value.street,
            "new hand starts on preflop",
        )
    }

    @Test
    fun advanceToNextHand_rotatesButton() = runUnitTest {
        val session = buildSession(seatsCount = 2)
        launch { session.runUntilHumansTurnOrComplete() }
        runCurrent()
        val firstButton = session.gameStateFlow.value.buttonSeatIndex

        launch { session.submitHumanIntent(PlayerIntent.Fold(seatIndex = 0)) }
        advanceUntilIdle()
        session.advanceToNextHand()
        advanceUntilIdle()

        val secondButton = session.gameStateFlow.value.buttonSeatIndex
        assertEquals(
            (firstButton + 1) % 2,
            secondButton,
            "button moves forward one seat",
        )
    }

    @Test
    fun advanceToNextHand_putsAllSeatsBackInHand() = runUnitTest {
        // Rebuy logic kicks in for bust seats. Easier path: just verify everyone
        // is dealt into hand 2.
        val session = buildSession(seatsCount = 2)
        launch { session.runUntilHumansTurnOrComplete() }
        runCurrent()

        launch { session.submitHumanIntent(PlayerIntent.Fold(seatIndex = 0)) }
        advanceUntilIdle()
        session.advanceToNextHand()
        advanceUntilIdle()

        val state = session.gameStateFlow.value
        assertTrue(state.seats.all { it.handParticipation == HandParticipation.InHand })
    }

    // ---------- State flow integrity ----------

    @Test
    fun gameStateFlow_andLegacyTableUiStateFlow_areBothPopulated() = runUnitTest {
        val session = buildSession(seatsCount = 2)
        launch { session.runUntilHumansTurnOrComplete() }
        runCurrent()

        val gameState = session.gameStateFlow.value
        val tableState = session.state.value
        assertEquals(1, gameState.handNumber)
        assertNull(
            (tableState as? TableUiState.Loading)?.let { "still loading" },
            "tableUiState should be past loading once startNextHand ran",
        )
    }

    // ---------- Helpers ----------

    private fun buildSession(
        seatsCount: Int = 2,
        humanSeatIndex: Int = 0,
        random: Random = Random(42L),
        onHandEnded: (GameEvent.HandEnded, GameState, Long) -> Unit = { _, _, _ -> },
    ): LocalBotsSession {
        val personalities = listOf(
            BotPersonality.Steve,
            BotPersonality.Jane,
            BotPersonality.David,
            BotPersonality.Mike,
            BotPersonality.Gina,
        ).take(seatsCount - 1)
        return LocalBotsSession(
            difficulty = BotDifficulty.Casual,
            humanSeatIndex = humanSeatIndex,
            botPersonalities = personalities,
            random = random,
            botActionDelayMs = 0L,
            botSpeedProvider = { com.dangerfield.cards.libraries.cards.BotSpeed.Fast },
            onHandEnded = onHandEnded,
            // All four production dispatchers route through the test scheduler,
            // so Monte Carlo (production: Dispatchers.Default) runs on virtual
            // time and respects advanceUntilIdle / runCurrent.
            dispatchers = dispatchers,
        )
    }
}
