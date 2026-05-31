package com.dangerfield.cards.libraries.gameplay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Named edge-case scenarios for [GameEngine] (testing-plan Round 3 §Edge-case
 * scenarios). Where [GameEnginePropertyTest] proves invariants hold across
 * randomized hands and [GameEngineAdvancedTest] pins crafted-deck showdowns,
 * this suite pins the specific hard-corner flows the plan enumerates:
 *
 *  - **Fold around to BB** — the blind-winner accounting.
 *  - **All-in preflop run-out** — the engine deals every street in one go.
 *  - **Three different all-in stacks** — three nested pots with shrinking
 *    eligibility.
 *  - **Three-way tie with a short stack** — split-pot math and side-pot math
 *    resolving simultaneously.
 *  - **Single contender after a postflop fold** — the engine fast-forwards to
 *    `PotAwarded` without dealing the remaining streets.
 *  - **`isBot` is engine-invisible** — identical hands resolve identically
 *    regardless of the seat metadata flag.
 *
 * Already pinned elsewhere (not duplicated here): board-plays chops
 * ([GameEngineAdvancedTest.showdown_splitPotOnTie]) and zero-stack seats being
 * sat out ([GameEngineAdvancedTest.startHand_skipsSeatsWithZeroStack]).
 */
class GameEngineEdgeCaseTest {

    private fun standardSettings() = RoomSettings(
        smallBlind = 5,
        bigBlind = 10,
        startingStack = 1_000,
        maxSeats = 6,
        turnTimerSeconds = 30,
    )

    private fun seat(
        index: Int,
        stack: Long = 1_000,
        status: SeatStatus = SeatStatus.Active,
        isBot: Boolean = false,
    ) = Seat(
        index = index,
        playerId = "p$index",
        displayName = "P$index",
        stack = stack,
        seatStatus = status,
        handParticipation = HandParticipation.InHand,
        isBot = isBot,
    )

    private fun craftDeck(
        seatHoleCards: List<List<Card>>,
        community: List<Card>,
    ): Deck {
        val ordered = mutableListOf<Card>()
        seatHoleCards.forEach { hole ->
            require(hole.size == 2) { "Each seat needs 2 hole cards" }
            ordered.addAll(hole)
        }
        ordered.addAll(community)
        val used = ordered.toSet()
        require(used.size == ordered.size) { "Duplicate cards in crafted scenario: $ordered" }
        ordered.addAll(Card.fullDeck.filterNot { it in used })
        return Deck.fromOrdered(ordered)
    }

    private fun apply(state: GameState, intent: PlayerIntent): GameState =
        GameEngine.applyIntent(state, intent).state

    // ===========================================================
    // Fold around to the big blind
    // ===========================================================

    @Test
    fun foldAroundToBB_bbWinsBothBlindsByFold() {
        // 3-handed, button=0: UTG=seat0, SB=seat1, BB=seat2. UTG folds, SB
        // folds, BB is the lone contender and wins the posted blinds.
        var s = GameEngine.startHand(
            settings = standardSettings(),
            seats = listOf(seat(0), seat(1), seat(2)),
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(42L),
        ).state
        s = apply(s, PlayerIntent.Fold(s.actingSeatIndex!!)) // UTG (seat 0)
        val end = GameEngine.applyIntent(s, PlayerIntent.Fold(s.actingSeatIndex!!)) // SB (seat 1)

        val ended = end.events.filterIsInstance<GameEvent.HandEnded>().single()
        val winner = ended.winners.single()
        assertEquals(2, winner.seatIndex, "BB is the lone contender")
        assertTrue(winner.byFold, "won by fold, not showdown")
        assertEquals(15L, winner.amount, "pot = SB 5 + BB 10")
        assertEquals(1_005L, end.state.seatAt(2).stack, "BB nets the small blind: 990 + 15")
        assertEquals(995L, end.state.seatAt(1).stack, "SB is down its posted 5")
        assertEquals(1_000L, end.state.seatAt(0).stack, "button posted nothing")
        assertTrue(end.state.community.isEmpty(), "no community dealt on a preflop fold-around")
    }

    // ===========================================================
    // Everyone all-in preflop → run-out
    // ===========================================================

    @Test
    fun allInPreflop_runsOutEveryStreetToShowdown() {
        val seats = listOf(seat(0, stack = 100), seat(1, stack = 100), seat(2, stack = 100))
        // seat 0 makes a spade flush; seats 1 and 2 only have ace-high.
        val deck = craftDeck(
            seatHoleCards = listOf(
                listOf(Card(Rank.Ace, Suit.Spades), Card(Rank.King, Suit.Spades)),
                listOf(Card(Rank.Ace, Suit.Clubs), Card(Rank.Queen, Suit.Diamonds)),
                listOf(Card(Rank.Ace, Suit.Hearts), Card(Rank.Jack, Suit.Diamonds)),
            ),
            community = listOf(
                Card(Rank.Two, Suit.Spades),
                Card(Rank.Five, Suit.Spades),
                Card(Rank.Nine, Suit.Spades),
                Card(Rank.Three, Suit.Clubs),
                Card(Rank.Seven, Suit.Diamonds),
            ),
        )
        var s = GameEngine.startHand(
            settings = standardSettings(),
            seats = seats,
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deck,
        ).state
        var events = emptyList<GameEvent>()
        while (s.actingSeatIndex != null) {
            val r = GameEngine.applyIntent(s, PlayerIntent.AllIn(s.actingSeatIndex!!))
            s = r.state
            events = events + r.events
        }

        assertEquals(BettingRound.Complete, s.street)
        assertEquals(5, s.community.size, "all five board cards are dealt in the run-out")
        assertEquals(1, events.filterIsInstance<GameEvent.HandEnded>().size, "exactly one hand end")
        assertEquals(300L, s.seats.sumOf { it.stack }, "chips conserved (3 × 100)")
        assertEquals(300L, s.seatAt(0).stack, "the flush scoops the single pot")
        assertEquals(HandCategory.Flush, events.filterIsInstance<GameEvent.HandEnded>()
            .single().winners.single().handRank?.category)
    }

    // ===========================================================
    // Three different all-in stacks → three nested pots
    // ===========================================================

    @Test
    fun threeDifferentAllInStacks_buildThreeNestedPots() {
        val seats = listOf(seat(0, stack = 50), seat(1, stack = 100), seat(2, stack = 200))
        var s = GameEngine.startHand(
            settings = standardSettings(),
            seats = seats,
            handNumber = 1,
            buttonSeatIndex = 2,
            deck = deterministicDeck(7L),
        ).state
        while (s.actingSeatIndex != null) {
            s = apply(s, PlayerIntent.AllIn(s.actingSeatIndex!!))
        }

        assertEquals(BettingRound.Complete, s.street)
        assertEquals(3, s.pots.size, "main + two side pots from three distinct stack depths")
        assertEquals(listOf(150L, 100L, 100L), s.pots.map { it.amount }, "50×3, 50×2, 100×1")
        assertEquals(setOf(0, 1, 2), s.pots[0].eligibleSeatIndexes.toSet())
        assertEquals(setOf(1, 2), s.pots[1].eligibleSeatIndexes.toSet())
        assertEquals(setOf(2), s.pots[2].eligibleSeatIndexes.toSet())
        assertEquals(350L, s.seats.sumOf { it.stack }, "chips conserved (50 + 100 + 200)")
    }

    // ===========================================================
    // Three-way tie with a short stack → split + side pot at once
    // ===========================================================

    @Test
    fun threeWayTieWithShortStack_splitsMainAndSidePotsSimultaneously() {
        val seats = listOf(seat(0, stack = 50), seat(1, stack = 100), seat(2, stack = 100))
        // All three play the board straight T-J-Q-K-A; none make a flush.
        val deck = craftDeck(
            seatHoleCards = listOf(
                listOf(Card(Rank.Two, Suit.Spades), Card(Rank.Three, Suit.Spades)),
                listOf(Card(Rank.Two, Suit.Hearts), Card(Rank.Three, Suit.Hearts)),
                listOf(Card(Rank.Two, Suit.Diamonds), Card(Rank.Three, Suit.Diamonds)),
            ),
            community = listOf(
                Card(Rank.Ten, Suit.Clubs),
                Card(Rank.Jack, Suit.Diamonds),
                Card(Rank.Queen, Suit.Clubs),
                Card(Rank.King, Suit.Diamonds),
                Card(Rank.Ace, Suit.Clubs),
            ),
        )
        var s = GameEngine.startHand(
            settings = standardSettings(),
            seats = seats,
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deck,
        ).state
        var events = emptyList<GameEvent>()
        while (s.actingSeatIndex != null) {
            val r = GameEngine.applyIntent(s, PlayerIntent.AllIn(s.actingSeatIndex!!))
            s = r.state
            events = events + r.events
        }

        assertEquals(2, s.pots.size, "main (50×3) + side (50×2)")
        assertEquals(listOf(150L, 100L), s.pots.map { it.amount })
        val ended = events.filterIsInstance<GameEvent.HandEnded>().single()
        // Main pot splits three ways (50 each), side pot splits two ways (50 each).
        assertEquals(5, ended.winners.size, "3 main-pot shares + 2 side-pot shares")
        assertTrue(ended.winners.none { it.byFold }, "all showdown wins")
        assertEquals(50L, s.seatAt(0).stack, "short stack: 50 of the main pot, even with the rest")
        assertEquals(100L, s.seatAt(1).stack, "50 main + 50 side")
        assertEquals(100L, s.seatAt(2).stack, "50 main + 50 side")
        assertEquals(250L, s.seats.sumOf { it.stack }, "chips conserved")
    }

    // ===========================================================
    // Single contender after a postflop fold → fast-forward
    // ===========================================================

    @Test
    fun postflopFold_singleContender_stopsDealingCommunity() {
        var s = GameEngine.startHand(
            settings = standardSettings(),
            seats = listOf(seat(0), seat(1)),
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(42L),
        ).state
        // Heads-up to the flop: button/SB completes, BB checks the option.
        s = apply(s, PlayerIntent.Call(0))
        s = apply(s, PlayerIntent.Check(1))
        assertEquals(BettingRound.Flop, s.street)
        assertEquals(3, s.community.size)

        // First to act postflop is the non-button (seat 1). It bets, seat 0 folds.
        s = apply(s, PlayerIntent.Bet(1, amount = 20))
        val end = GameEngine.applyIntent(s, PlayerIntent.Fold(0))

        assertEquals(BettingRound.Complete, end.state.street)
        assertEquals(3, end.state.community.size, "turn and river are never dealt after the fold-around")
        val winner = end.events.filterIsInstance<GameEvent.HandEnded>().single().winners.single()
        assertEquals(1, winner.seatIndex)
        assertTrue(winner.byFold)
    }

    // ===========================================================
    // isBot is engine-invisible
    // ===========================================================

    @Test
    fun isBotFlag_doesNotChangeEngineBehaviour() {
        fun play(bots: Boolean): GameState {
            var s = GameEngine.startHand(
                settings = standardSettings(),
                seats = listOf(seat(0, isBot = bots), seat(1, isBot = bots), seat(2, isBot = bots)),
                handNumber = 1,
                buttonSeatIndex = 0,
                deck = deterministicDeck(99L),
            ).state
            s = apply(s, PlayerIntent.Fold(s.actingSeatIndex!!))
            return GameEngine.applyIntent(s, PlayerIntent.Fold(s.actingSeatIndex!!)).state
        }

        val humans = play(bots = false)
        val bots = play(bots = true)
        assertEquals(
            humans.seats.map { it.copy(isBot = false) },
            bots.seats.map { it.copy(isBot = false) },
            "the engine resolves the hand identically; only the metadata flag differs",
        )
        assertEquals(humans.pots, bots.pots)
        assertEquals(humans.street, bots.street)
    }
}
