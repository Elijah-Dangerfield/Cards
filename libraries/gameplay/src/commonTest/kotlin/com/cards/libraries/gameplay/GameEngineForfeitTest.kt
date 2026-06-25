package com.dangerfield.cards.libraries.gameplay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Crafted edge cases for [GameEngine.forfeitSeat] — the mid-hand fold-out used
 * when a player leaves or is removed. Property-level invariants are covered by
 * [GameEnginePropertyTest]; these pin the specific action-flow outcomes.
 */
class GameEngineForfeitTest {

    private val settings = RoomSettings(
        smallBlind = 5,
        bigBlind = 10,
        startingStack = 1_000,
        maxSeats = 9,
        turnTimerSeconds = 30,
    )

    private fun startHand(seatCount: Int, button: Int = 0): GameState =
        GameEngine.startHand(
            settings = settings,
            seats = List(seatCount) { i ->
                Seat(i, "p$i", "P$i", settings.startingStack, SeatStatus.Active, HandParticipation.InHand)
            },
            handNumber = 1,
            buttonSeatIndex = button,
            deck = deterministicDeck(7),
        ).state

    @Test
    fun forfeitActingSeat_headsUp_endsHand_otherPlayerWins() {
        val state = startHand(seatCount = 2, button = 0)
        val actor = state.actingSeatIndex!!

        val result = GameEngine.forfeitSeat(state, actor)

        assertEquals(BettingRound.Complete, result.state.street, "one contender left → hand over")
        assertEquals(null, result.state.actingSeatIndex)
        assertEquals(HandParticipation.Folded, result.state.seatAt(actor).handParticipation)
        val winner = if (actor == 0) 1 else 0
        assertTrue(
            result.events.filterIsInstance<GameEvent.PotAwarded>().any { it.seatIndex == winner },
            "the remaining player wins the pot",
        )
    }

    @Test
    fun forfeitActingSeat_threeHanded_advancesActionToNextSeat() {
        val state = startHand(seatCount = 3, button = 0)
        val actor = state.actingSeatIndex!!

        val result = GameEngine.forfeitSeat(state, actor)

        assertEquals(BettingRound.Preflop, result.state.street, "two contenders remain → hand continues")
        val next = result.state.actingSeatIndex
        assertTrue(next != null && next != actor, "action moves off the forfeited seat")
        assertTrue(result.state.seatAt(next!!).canAct, "the new actor can act")
        assertEquals(HandParticipation.Folded, result.state.seatAt(actor).handParticipation)
    }

    @Test
    fun forfeitNonActingSeat_threeHanded_keepsActionOnCurrentActor() {
        val state = startHand(seatCount = 3, button = 0)
        val actor = state.actingSeatIndex!!
        val bystander = (0..2).first { it != actor }

        val result = GameEngine.forfeitSeat(state, bystander)

        assertEquals(actor, result.state.actingSeatIndex, "the current actor still owes a decision")
        assertEquals(BettingRound.Preflop, result.state.street)
        assertEquals(HandParticipation.Folded, result.state.seatAt(bystander).handParticipation)
    }

    @Test
    fun forfeitAlreadyFoldedSeat_isNoOp() {
        val state = startHand(seatCount = 3, button = 0)
        val actor = state.actingSeatIndex!!
        // Fold the actor through the normal path first.
        val afterFold = GameEngine.applyIntent(state, PlayerIntent.Fold(actor)).state

        val result = GameEngine.forfeitSeat(afterFold, actor)

        assertEquals(afterFold, result.state, "forfeiting an already-folded seat changes nothing")
        assertTrue(result.events.isEmpty(), "and emits no events")
    }

    @Test
    fun forfeitOnCompleteHand_isNoOp() {
        val state = startHand(seatCount = 2, button = 0)
        val actor = state.actingSeatIndex!!
        val complete = GameEngine.forfeitSeat(state, actor).state
        assertEquals(BettingRound.Complete, complete.street)

        val result = GameEngine.forfeitSeat(complete, if (actor == 0) 1 else 0)

        assertEquals(complete, result.state, "no forfeit once the hand is over")
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun forfeit_isIdempotent() {
        val state = startHand(seatCount = 3, button = 0)
        val actor = state.actingSeatIndex!!

        val once = GameEngine.forfeitSeat(state, actor).state
        val twice = GameEngine.forfeitSeat(once, actor)

        assertEquals(once, twice.state, "a repeat forfeit is a no-op")
        assertTrue(twice.events.isEmpty())
    }

    @Test
    fun forfeitFacingAllIn_headsUp_runsOutAndAwardsTheAllInPlayer() {
        // Seat 0 shoves all-in; seat 1 (facing the shove) leaves instead of calling,
        // so it's forfeited. Heads-up, the facing seat IS the acting seat, so folding
        // it leaves no one to act and the street completes — the board runs out and
        // the all-in seat 0 takes the pot at showdown. This is the path an over-the-
        // wire deferred all-in settlement (MP-17) relies on: a leaver facing the
        // shove must still resolve the hand so the all-in player is paid.
        var state = startHand(seatCount = 2, button = 0)
        state = GameEngine.applyIntent(state, PlayerIntent.AllIn(state.actingSeatIndex!!)).state
        val allInSeat = state.seats.first { it.handParticipation == HandParticipation.AllIn }.index
        val facing = state.actingSeatIndex!!

        val result = GameEngine.forfeitSeat(state, facing)

        assertEquals(BettingRound.Complete, result.state.street, "no one left to act → hand resolves")
        assertEquals(null, result.state.actingSeatIndex)
        assertEquals(HandParticipation.Folded, result.state.seatAt(facing).handParticipation)
        assertTrue(
            result.events.filterIsInstance<GameEvent.PotAwarded>().any { it.seatIndex == allInSeat },
            "the all-in player is awarded the pot — never stranded",
        )
    }

    @Test
    fun forfeitAllInSeat_isNoOp_theyKeepTheirShowdownRight() {
        // Drive a heads-up hand to where one seat is all-in, then try to forfeit
        // them — an all-in player is entitled to the showdown even if they leave.
        var state = startHand(seatCount = 2, button = 0)
        state = GameEngine.applyIntent(state, PlayerIntent.AllIn(state.actingSeatIndex!!)).state
        val allInSeat = state.seats.first { it.handParticipation == HandParticipation.AllIn }.index

        val result = GameEngine.forfeitSeat(state, allInSeat)

        assertEquals(state, result.state, "an all-in seat can't be forfeited")
        assertTrue(result.events.isEmpty())
    }
}
