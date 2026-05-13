package com.dangerfield.cards.libraries.bots

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameEngine
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.gameplay.SeatStatus
import com.dangerfield.cards.libraries.gameplay.deterministicDeck
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BotDecisionTest {

    private val baseSettings = RoomSettings(
        smallBlind = 5,
        bigBlind = 10,
        startingStack = 1_000,
        maxSeats = 6,
        turnTimerSeconds = 30,
    )

    @Test
    fun casualBotChecksWithoutOpenBet() {
        val seats = listOf(
            seat(0, stack = 1_000),
            seat(1, stack = 1_000),
        )
        var state = GameEngine.startHand(baseSettings, seats, handNumber = 1, buttonSeatIndex = 0, deck = deterministicDeck(42)).state
        while (state.street == BettingRound.Preflop) {
            state = GameEngine.applyIntent(
                state,
                PlayerIntent.Call(state.actingSeatIndex!!),
            ).state
            if (state.actingSeatIndex == null) break
            if (state.currentBetThisStreet == state.seatAt(state.actingSeatIndex!!).contributedThisStreet) {
                state = GameEngine.applyIntent(state, PlayerIntent.Check(state.actingSeatIndex!!)).state
            }
        }
        if (state.street == BettingRound.Flop && state.actingSeatIndex != null) {
            val decision = BotDecision.choose(
                state = state,
                seatIndex = state.actingSeatIndex!!,
                personality = BotPersonality.Jane,
                difficulty = BotDifficulty.Casual,
                random = Random(7L),
            )
            val intent = decision.intent
            assertTrue(
                intent is PlayerIntent.Check || intent is PlayerIntent.Bet,
                "casual bot should check or value-bet; got $intent",
            )
        }
    }

    @Test
    fun strongHandChoosesAggression() {
        val seats = listOf(
            seatWithHoleCards(0, stack = 1_000, hole = listOf("As", "Ah")),
            seat(1, stack = 1_000),
        )
        var state = GameEngine.startHand(baseSettings, seats, handNumber = 1, buttonSeatIndex = 0, deck = deterministicDeck(101)).state
        state = state.copy(
            seats = state.seats.map {
                if (it.index == 0) it.copy(holeCards = listOf(card("As"), card("Ah"))) else it
            }
        )
        val decision = BotDecision.choose(
            state = state,
            seatIndex = state.actingSeatIndex!!,
            personality = BotPersonality.David,
            difficulty = BotDifficulty.Standard,
            random = Random(0L),
        )
        val intent = decision.intent
        assertTrue(
            intent is PlayerIntent.Raise || intent is PlayerIntent.Bet || intent is PlayerIntent.AllIn,
            "AA should choose aggression, got $intent",
        )
    }

    @Test
    fun weakHandFoldsToBigBet() {
        val seats = listOf(
            seatWithHoleCards(0, stack = 1_000, hole = listOf("2c", "7d")),
            seat(1, stack = 1_000),
        )
        var state = GameEngine.startHand(baseSettings, seats, handNumber = 1, buttonSeatIndex = 1, deck = deterministicDeck(202)).state
        state = state.copy(
            seats = state.seats.map {
                if (it.index == 0) it.copy(holeCards = listOf(card("2c"), card("7d"))) else it
            }
        )
        state = GameEngine.applyIntent(state, PlayerIntent.Raise(state.actingSeatIndex!!, totalAmountThisStreet = 500)).state

        val decision = BotDecision.choose(
            state = state,
            seatIndex = state.actingSeatIndex!!,
            personality = BotPersonality.Jane,
            difficulty = BotDifficulty.Casual,
            random = Random(0L),
        )
        assertTrue(
            decision.intent is PlayerIntent.Fold,
            "tight casual bot should fold trash facing a huge bet, got ${decision.intent}",
        )
    }

    @Test
    fun thoughtIncludesRationale() {
        val seats = listOf(
            seatWithHoleCards(0, stack = 1_000, hole = listOf("Ks", "Kh")),
            seat(1, stack = 1_000),
        )
        var state = GameEngine.startHand(baseSettings, seats, handNumber = 1, buttonSeatIndex = 1, deck = deterministicDeck(7L)).state
        state = state.copy(
            seats = state.seats.map {
                if (it.index == 0) it.copy(holeCards = listOf(card("Ks"), card("Kh"))) else it
            }
        )
        val decision = BotDecision.choose(
            state = state,
            seatIndex = state.actingSeatIndex!!,
            personality = BotPersonality.Gina,
            difficulty = BotDifficulty.Standard,
            random = Random(0L),
        )
        assertTrue(decision.thought.rationale.contains("strength="), "rationale missing strength")
        assertTrue(decision.thought.handStrength > 0.5, "KK should be strong, was ${decision.thought.handStrength}")
    }

    @Test
    fun botsBeatNaiveAllInShoverOverManyHands() {
        val rng = Random(0xACCE_BEEFL)
        var botStack = 5_000L
        var shoverStack = 5_000L
        var handNumber = 0
        var iterations = 0
        val maxIterations = 60
        while (iterations < maxIterations && botStack > 0 && shoverStack > 0) {
            iterations++
            val effective = minOf(botStack, shoverStack)
            val perPlayerStack = effective.coerceAtMost(1_000L)
            if (perPlayerStack < baseSettings.bigBlind * 2) break
            handNumber++

            val seats = listOf(
                seat(0, stack = perPlayerStack),
                seat(1, stack = perPlayerStack),
            )
            val tracker = OpponentTracker()
            var state = GameEngine.startHand(
                settings = baseSettings,
                seats = seats,
                handNumber = handNumber,
                buttonSeatIndex = if (handNumber % 2 == 0) 0 else 1,
                deck = deterministicDeck(seed = rng.nextLong()),
            ).also { it.events.forEach(tracker::observe) }.state

            val botSeat = 0
            val shoverSeat = 1

            while (state.street != BettingRound.Complete && state.actingSeatIndex != null) {
                val acting = state.actingSeatIndex!!
                val intent: PlayerIntent = if (acting == shoverSeat) {
                    PlayerIntent.AllIn(acting)
                } else {
                    BotDecision.choose(
                        state = state,
                        seatIndex = acting,
                        personality = BotPersonality.Gina,
                        difficulty = BotDifficulty.Standard,
                        opponentTracker = tracker,
                        random = rng,
                        equityIterations = 120,
                    ).intent
                }
                val result = GameEngine.applyIntent(state, intent)
                result.events.forEach(tracker::observe)
                state = result.state
            }

            val finalBot = state.seatAt(botSeat).stack
            val finalShover = state.seatAt(shoverSeat).stack
            val botDelta = finalBot - perPlayerStack
            val shoverDelta = finalShover - perPlayerStack
            botStack += botDelta
            shoverStack += shoverDelta
        }

        val botProfit = botStack - 5_000L
        assertTrue(
            botProfit > 0,
            "Standard bot must end ahead of a naive all-in-shover (was botStack=$botStack after $iterations hands).",
        )
    }

    private fun seat(index: Int, stack: Long): Seat = Seat(
        index = index,
        playerId = "p$index",
        displayName = "P$index",
        stack = stack,
        seatStatus = SeatStatus.Active,
        handParticipation = HandParticipation.InHand,
    )

    private fun seatWithHoleCards(index: Int, stack: Long, hole: List<String>): Seat = seat(index, stack)

    private fun card(s: String) = com.dangerfield.cards.libraries.gameplay.Card.parse(s)
}
