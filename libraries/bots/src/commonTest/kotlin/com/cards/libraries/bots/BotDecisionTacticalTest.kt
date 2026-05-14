package com.dangerfield.cards.libraries.bots

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.GameEngine
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.gameplay.SeatStatus
import com.dangerfield.cards.libraries.gameplay.deterministicDeck
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class BotDecisionTacticalTest {

    private val settings = RoomSettings(
        smallBlind = 5,
        bigBlind = 10,
        startingStack = 1_000,
        maxSeats = 6,
        turnTimerSeconds = 30,
    )

    @Test
    fun stealSpotTagAppearsWhenFoldedAroundLatePosition() {
        // Preflop, bot acts last with no raises in front and the rest of the table folded.
        val state = buildPreflopState(handSeed = 11L, holeForActor = listOf("Td", "9d"))
        val acting = state.actingSeatIndex!!
        val context = HandContext(
            position = TablePosition.Late,
            streetActionsBeforeSelf = listOf(
                StreetAction(seatIndex = (acting + 1) % 6, action = PlayerAction.Fold),
                StreetAction(seatIndex = (acting + 2) % 6, action = PlayerAction.Fold),
                StreetAction(seatIndex = (acting + 3) % 6, action = PlayerAction.Fold),
            ),
            preflopAggressorSeatIndex = null,
            selfRaisedThisStreet = false,
        )
        val decision = BotDecision.choose(
            state = state,
            seatIndex = acting,
            personality = BotPersonality.Mike,
            difficulty = BotDifficulty.Standard,
            random = Random(0L),
            handContext = context,
        )
        assertTrue(
            decision.thought.rationale.contains("steal"),
            "expected steal tag in rationale, got: ${decision.thought.rationale}",
        )
    }

    @Test
    fun facing3betTagAppearsAfterReRaise() {
        val state = buildPreflopState(handSeed = 22L, holeForActor = listOf("Qd", "Qs"))
        val acting = state.actingSeatIndex!!
        val opponent = (acting + 1) % 6
        val context = HandContext(
            position = TablePosition.Middle,
            streetActionsBeforeSelf = listOf(
                StreetAction(seatIndex = acting, action = PlayerAction.Raise(totalStreetContribution = 30, raiseAmount = 30)),
                StreetAction(seatIndex = opponent, action = PlayerAction.Raise(totalStreetContribution = 90, raiseAmount = 60)),
            ),
            preflopAggressorSeatIndex = opponent,
            selfRaisedThisStreet = true,
        )
        val decision = BotDecision.choose(
            state = state.copy(
                currentBetThisStreet = 90,
                lastFullRaiseSize = 60,
            ),
            seatIndex = acting,
            personality = BotPersonality.Jane,
            difficulty = BotDifficulty.Standard,
            random = Random(0L),
            handContext = context,
        )
        assertTrue(
            decision.thought.rationale.contains("facing-3bet"),
            "expected facing-3bet tag, got: ${decision.thought.rationale}",
        )
    }

    @Test
    fun stealSpotMakesManiacRaiseMoreOftenWithMarginalHand() {
        // Compare raise frequency for Mike with a marginal hand:
        // (a) with no tactical context vs (b) folded around in late position.
        // Mike's aggression + steal incentive should produce strictly more raises.
        var raisesWithSteal = 0
        var raisesWithout = 0
        val trials = 30
        repeat(trials) { i ->
            val state = buildPreflopState(handSeed = 1_000L + i, holeForActor = listOf("8s", "4d"))
            val acting = state.actingSeatIndex!!

            val noCtx = BotDecision.choose(
                state = state,
                seatIndex = acting,
                personality = BotPersonality.Mike,
                difficulty = BotDifficulty.Standard,
                random = Random(2_000L + i),
                handContext = HandContext.Empty,
            )
            if (noCtx.intent is PlayerIntent.Raise || noCtx.intent is PlayerIntent.Bet || noCtx.intent is PlayerIntent.AllIn) {
                raisesWithout += 1
            }

            val stealCtx = HandContext(
                position = TablePosition.Late,
                streetActionsBeforeSelf = listOf(
                    StreetAction((acting + 1) % 6, PlayerAction.Fold),
                    StreetAction((acting + 2) % 6, PlayerAction.Fold),
                    StreetAction((acting + 3) % 6, PlayerAction.Fold),
                ),
                preflopAggressorSeatIndex = null,
                selfRaisedThisStreet = false,
            )
            val withSteal = BotDecision.choose(
                state = state,
                seatIndex = acting,
                personality = BotPersonality.Mike,
                difficulty = BotDifficulty.Standard,
                random = Random(2_000L + i),
                handContext = stealCtx,
            )
            if (withSteal.intent is PlayerIntent.Raise || withSteal.intent is PlayerIntent.Bet || withSteal.intent is PlayerIntent.AllIn) {
                raisesWithSteal += 1
            }
        }
        assertTrue(
            raisesWithSteal >= raisesWithout,
            "expected steal context to raise at least as often: with=$raisesWithSteal without=$raisesWithout",
        )
        assertTrue(
            raisesWithSteal > raisesWithout,
            "expected strictly more raises under steal context: with=$raisesWithSteal without=$raisesWithout",
        )
    }

    private fun buildPreflopState(
        handSeed: Long,
        holeForActor: List<String>,
    ): com.dangerfield.cards.libraries.gameplay.GameState {
        val seats = (0 until 6).map { idx ->
            Seat(
                index = idx,
                playerId = "p$idx",
                displayName = "P$idx",
                stack = 1_000,
                seatStatus = SeatStatus.Active,
                handParticipation = HandParticipation.InHand,
            )
        }
        val state = GameEngine.startHand(
            settings = settings,
            seats = seats,
            handNumber = 1,
            buttonSeatIndex = 5,
            deck = deterministicDeck(handSeed),
        ).state
        check(state.street == BettingRound.Preflop)
        val actingIndex = state.actingSeatIndex
            ?: error("expected preflop actor")
        // Force the actor's hole cards so strength is deterministic across trials.
        return state.copy(
            seats = state.seats.map {
                if (it.index == actingIndex) it.copy(holeCards = holeForActor.map(Card::parse)) else it
            },
        )
    }
}
