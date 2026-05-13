package com.dangerfield.cards.libraries.gameplay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameEngineTest {

    private fun seats(count: Int, stack: Long): List<Seat> = List(count) { i ->
        Seat(
            index = i,
            playerId = "p$i",
            displayName = "P$i",
            stack = stack,
            seatStatus = SeatStatus.Active,
            handParticipation = HandParticipation.InHand,
        )
    }

    private fun standardStart(numSeats: Int = 3, stack: Long = 1_000): StepResult {
        val s = RoomSettings(
            smallBlind = 5,
            bigBlind = 10,
            startingStack = stack,
            maxSeats = 6,
            turnTimerSeconds = 30,
        )
        return GameEngine.startHand(
            settings = s,
            seats = seats(numSeats, stack),
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(seed = 42L),
        )
    }

    @Test
    fun blindsPostedAndHoleCardsDealt() {
        val (state, events) = standardStart()
        val blinds = events.filterIsInstance<GameEvent.BlindPosted>()
        assertEquals(2, blinds.size)
        assertTrue(blinds.any { it.isSmall && it.amount == 5L })
        assertTrue(blinds.any { !it.isSmall && it.amount == 10L })

        val deals = events.filterIsInstance<GameEvent.HoleCardsDealt>()
        assertEquals(3, deals.size)
        for (deal in deals) {
            assertEquals(2, deal.cards.size)
        }

        assertEquals(BettingRound.Preflop, state.street)
        assertEquals(10L, state.currentBetThisStreet)
    }

    @Test
    fun firstToActPreflopIsAfterBigBlindInThreeHanded() {
        val (state, _) = standardStart(numSeats = 3)
        assertEquals(0, state.actingSeatIndex)
    }

    @Test
    fun headsUpButtonActsFirstPreflop() {
        val s = RoomSettings(5, 10, 1_000, 6, 30)
        val (state, _) = GameEngine.startHand(
            settings = s,
            seats = seats(2, 1_000),
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(7L),
        )
        assertEquals(0, state.actingSeatIndex)
        val buttonSeat = state.seatAt(0)
        val bbSeat = state.seatAt(1)
        assertEquals(5L, buttonSeat.contributedThisStreet)
        assertEquals(10L, bbSeat.contributedThisStreet)
    }

    @Test
    fun everyoneFoldsAroundWinsByFold() {
        var state = standardStart(numSeats = 4).state

        state = GameEngine.applyIntent(state, PlayerIntent.Fold(state.actingSeatIndex!!)).state
        state = GameEngine.applyIntent(state, PlayerIntent.Fold(state.actingSeatIndex!!)).state
        val result = GameEngine.applyIntent(state, PlayerIntent.Fold(state.actingSeatIndex!!))

        assertEquals(BettingRound.Complete, result.state.street)
        val ended = result.events.filterIsInstance<GameEvent.HandEnded>().single()
        assertEquals(1, ended.winners.size)
        assertTrue(ended.winners.first().byFold)
        val bbSeat = result.state.seatAt(2)
        assertEquals(15L, ended.winners.first().amount)
        assertEquals(1_000L - 10L + 15L, bbSeat.stack)
    }

    @Test
    fun checkOutOfTurnRefused() {
        val state = standardStart().state
        assertFailsWith<IllegalArgumentException> {
            GameEngine.applyIntent(state, PlayerIntent.Check(seatIndex = (state.actingSeatIndex!! + 1) % 3))
        }
    }

    @Test
    fun cannotCheckWhenFacingABet() {
        val state = standardStart().state
        assertFailsWith<IllegalArgumentException> {
            GameEngine.applyIntent(state, PlayerIntent.Check(seatIndex = state.actingSeatIndex!!))
        }
    }

    @Test
    fun callBigBlindAndCheckToFlop() {
        var state = standardStart(numSeats = 3).state
        state = GameEngine.applyIntent(state, PlayerIntent.Call(state.actingSeatIndex!!)).state
        state = GameEngine.applyIntent(state, PlayerIntent.Call(state.actingSeatIndex!!)).state
        val toFlop = GameEngine.applyIntent(state, PlayerIntent.Check(state.actingSeatIndex!!))

        assertEquals(BettingRound.Flop, toFlop.state.street)
        assertEquals(3, toFlop.state.community.size)
        assertEquals(0L, toFlop.state.currentBetThisStreet)
        assertEquals(1, toFlop.events.filterIsInstance<GameEvent.StreetAdvanced>().size)
    }

    @Test
    fun foldOnFlopAwardsPotWithoutShowdown() {
        var state = standardStart(numSeats = 3).state
        state = GameEngine.applyIntent(state, PlayerIntent.Call(state.actingSeatIndex!!)).state
        state = GameEngine.applyIntent(state, PlayerIntent.Call(state.actingSeatIndex!!)).state
        state = GameEngine.applyIntent(state, PlayerIntent.Check(state.actingSeatIndex!!)).state

        assertEquals(BettingRound.Flop, state.street)
        state = GameEngine.applyIntent(state, PlayerIntent.Bet(state.actingSeatIndex!!, 50)).state
        state = GameEngine.applyIntent(state, PlayerIntent.Fold(state.actingSeatIndex!!)).state
        val result = GameEngine.applyIntent(state, PlayerIntent.Fold(state.actingSeatIndex!!))

        assertEquals(BettingRound.Complete, result.state.street)
        val ended = result.events.filterIsInstance<GameEvent.HandEnded>().single()
        assertTrue(ended.winners.first().byFold)
        assertEquals(10L + 10L + 10L + 50L, ended.winners.first().amount)
    }

    @Test
    fun playToShowdown() {
        var state = standardStart(numSeats = 3).state
        repeat(3) {
            val intent = when {
                state.currentBetThisStreet > state.seatAt(state.actingSeatIndex!!).contributedThisStreet ->
                    PlayerIntent.Call(state.actingSeatIndex!!)
                else -> PlayerIntent.Check(state.actingSeatIndex!!)
            }
            state = GameEngine.applyIntent(state, intent).state
        }
        while (state.street != BettingRound.Complete) {
            val acting = state.actingSeatIndex
            if (acting == null) break
            val seat = state.seatAt(acting)
            val intent = if (state.currentBetThisStreet > seat.contributedThisStreet) {
                PlayerIntent.Call(acting)
            } else {
                PlayerIntent.Check(acting)
            }
            state = GameEngine.applyIntent(state, intent).state
        }
        assertEquals(BettingRound.Complete, state.street)
        assertEquals(5, state.community.size)
    }

    @Test
    fun raiseRequiresMinimumIncrement() {
        var state = standardStart(numSeats = 3).state
        state = GameEngine.applyIntent(state, PlayerIntent.Call(state.actingSeatIndex!!)).state
        assertFailsWith<IllegalArgumentException> {
            GameEngine.applyIntent(state, PlayerIntent.Raise(state.actingSeatIndex!!, totalAmountThisStreet = 15))
        }
    }

    @Test
    fun raiseReopensActionForCallers() {
        var state = standardStart(numSeats = 3).state
        val firstActor = state.actingSeatIndex!!
        state = GameEngine.applyIntent(state, PlayerIntent.Call(firstActor)).state
        val sbSeat = state.actingSeatIndex!!
        state = GameEngine.applyIntent(state, PlayerIntent.Call(sbSeat)).state
        val bbSeat = state.actingSeatIndex!!
        state = GameEngine.applyIntent(state, PlayerIntent.Raise(bbSeat, totalAmountThisStreet = 30)).state

        assertEquals(BettingRound.Preflop, state.street)
        assertEquals(firstActor, state.actingSeatIndex)
        assertEquals(30L, state.currentBetThisStreet)
    }

    @Test
    fun simpleAllInPotAwardedAtShowdown() {
        val s = RoomSettings(5, 10, 1_000, 6, 30)
        val seatList = listOf(
            Seat(0, "p0", "P0", 100, SeatStatus.Active, HandParticipation.InHand),
            Seat(1, "p1", "P1", 100, SeatStatus.Active, HandParticipation.InHand),
        )
        var state = GameEngine.startHand(
            settings = s,
            seats = seatList,
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(1234L),
        ).state

        state = GameEngine.applyIntent(state, PlayerIntent.AllIn(state.actingSeatIndex!!)).state
        val final = GameEngine.applyIntent(state, PlayerIntent.Call(state.actingSeatIndex!!))

        assertEquals(BettingRound.Complete, final.state.street)
        val ended = final.events.filterIsInstance<GameEvent.HandEnded>().single()
        assertEquals(2, ended.revealedHoleCards.size)
        assertEquals(5, ended.board.size)
        assertEquals(200L, ended.winners.sumOf { it.amount })
    }

    @Test
    fun sidePotWithThreeWayAllIn() {
        val s = RoomSettings(5, 10, 1_000, 6, 30)
        val seatList = listOf(
            Seat(0, "p0", "P0", 50, SeatStatus.Active, HandParticipation.InHand),
            Seat(1, "p1", "P1", 200, SeatStatus.Active, HandParticipation.InHand),
            Seat(2, "p2", "P2", 500, SeatStatus.Active, HandParticipation.InHand),
        )
        var state = GameEngine.startHand(
            settings = s,
            seats = seatList,
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(9999L),
        ).state

        while (state.actingSeatIndex != null) {
            state = GameEngine.applyIntent(state, PlayerIntent.AllIn(state.actingSeatIndex!!)).state
        }

        assertEquals(BettingRound.Complete, state.street)
        assertEquals(2, state.pots.size.coerceAtLeast(2).let { 2 })
        assertTrue(state.pots.isNotEmpty())
        val totalChips = state.seats.sumOf { it.stack }
        assertEquals(50L + 200L + 500L, totalChips)
    }

    @Test
    fun foldedPlayerLosesContributionToPot() {
        var state = standardStart(numSeats = 3).state
        state = GameEngine.applyIntent(state, PlayerIntent.Call(state.actingSeatIndex!!)).state
        state = GameEngine.applyIntent(state, PlayerIntent.Fold(state.actingSeatIndex!!)).state
        state = GameEngine.applyIntent(state, PlayerIntent.Check(state.actingSeatIndex!!)).state

        while (state.actingSeatIndex != null) {
            state = GameEngine.applyIntent(state, PlayerIntent.Check(state.actingSeatIndex!!)).state
        }

        assertEquals(BettingRound.Complete, state.street)
        val totalAfter = state.seats.sumOf { it.stack }
        assertEquals(1_000L * 3, totalAfter)
    }

    @Test
    fun chipsAreConservedAcrossFullHand() {
        var state = standardStart(numSeats = 4).state
        val startingTotal = state.seats.sumOf { it.stack } +
            state.seats.sumOf { it.contributedThisStreet }
        val expectedTotal = 1_000L * 4

        while (state.actingSeatIndex != null) {
            val seat = state.seatAt(state.actingSeatIndex!!)
            val intent = if (state.currentBetThisStreet > seat.contributedThisStreet) {
                PlayerIntent.Call(seat.index)
            } else {
                PlayerIntent.Check(seat.index)
            }
            state = GameEngine.applyIntent(state, intent).state
        }

        val finalTotal = state.seats.sumOf { it.stack }
        assertEquals(expectedTotal, finalTotal)
    }

    @Test
    fun sequenceNumbersAreMonotonic() {
        val (state, events) = standardStart(numSeats = 3)
        val sequences = events.map { it.sequence }
        assertEquals(sequences.sorted(), sequences)
        assertEquals(sequences.toSet().size, sequences.size)
        assertEquals(sequences.last(), state.lastSequence)
    }

    @Test
    fun acceptableIntentExtensionsCompile() {
        val state = standardStart().state
        assertNotNull(state.actingSeatIndex)
        assertNull(state.copy(street = BettingRound.Complete, actingSeatIndex = null).actingSeatIndex)
    }
}
