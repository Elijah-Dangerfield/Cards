package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.Pot
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.gameplay.SeatStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exhaustive coverage of [LegalActions.from] — the projection that decides
 * which action buttons (check / call / raise / all-in) the human sees and at
 * what amounts. Pure: builds a [GameState] + [Seat] and asserts the derived
 * [LegalActions] directly, no VM or coroutines.
 */
class LegalActionsProjectionTest {

    private val settings = RoomSettings.Default // SB 5 / BB 10

    @Test
    fun openBet_canCheck_cannotCall_andMinRaiseIsBigBlind() {
        val seat = seat(stack = 1_000, contributedThisStreet = 0)
        val legal = LegalActions.from(state(currentBet = 0, lastFullRaise = settings.bigBlind), seat)

        assertTrue(legal.canCheck)
        assertFalse(legal.canCall)
        assertEquals(0, legal.callAmount)
        assertTrue(legal.isOpenBet)
        assertEquals(settings.bigBlind, legal.minRaiseTotal)
        assertEquals(1_000, legal.maxRaiseTotal)
    }

    @Test
    fun facingABet_cannotCheck_canCall_atTheToCallAmount() {
        val seat = seat(stack = 1_000, contributedThisStreet = 0)
        val legal = LegalActions.from(state(currentBet = 50, lastFullRaise = 50), seat)

        assertFalse(legal.canCheck)
        assertTrue(legal.canCall)
        assertEquals(50, legal.callAmount)
        assertFalse(legal.isOpenBet)
        assertTrue(legal.canRaise)
        assertEquals(100, legal.minRaiseTotal) // currentBet 50 + lastFullRaise 50
        assertEquals(1_000, legal.maxRaiseTotal)
    }

    @Test
    fun partiallyInvested_callAmountIsTheRemainder() {
        val seat = seat(stack = 970, contributedThisStreet = 30)
        val legal = LegalActions.from(state(currentBet = 80, lastFullRaise = 50), seat)

        assertEquals(50, legal.callAmount) // 80 − 30 already in
        assertTrue(legal.canCall)
        assertEquals(1_000, legal.maxRaiseTotal) // 30 in + 970 stack
    }

    @Test
    fun betLargerThanStack_cannotCall_onlyAllIn() {
        val seat = seat(stack = 500, contributedThisStreet = 0)
        val legal = LegalActions.from(state(currentBet = 2_000, lastFullRaise = 2_000), seat)

        assertFalse(legal.canCheck)
        assertFalse(legal.canCall, "can't cover a 2000 bet with a 500 stack")
        assertFalse(legal.canRaise)
        assertTrue(legal.canAllIn)
        assertEquals(500, legal.allInAmount)
    }

    @Test
    fun alreadyMatched_canCheck() {
        val seat = seat(stack = 950, contributedThisStreet = 50)
        val legal = LegalActions.from(state(currentBet = 50, lastFullRaise = 50), seat)

        assertTrue(legal.canCheck)
        assertFalse(legal.canCall)
        assertEquals(0, legal.callAmount)
    }

    @Test
    fun potIfYouCall_sumsStreetContributions_settledPots_andYourCall() {
        val hero = seat(stack = 900, contributedThisStreet = 0)
        val villain = Seat(
            index = 1,
            playerId = "p1",
            displayName = "V",
            stack = 800,
            seatStatus = SeatStatus.Active,
            handParticipation = HandParticipation.InHand,
            contributedThisStreet = 100,
        )
        val s = state(
            currentBet = 100,
            lastFullRaise = 100,
            seats = listOf(hero, villain),
            pots = listOf(Pot(amount = 40, eligibleSeatIndexes = listOf(0, 1))),
        )
        val legal = LegalActions.from(s, hero)

        // 0 (hero street) + 100 (villain street) + 40 (settled pot) + 100 (hero's call)
        assertEquals(240, legal.potIfYouCall)
    }

    // ---------- builders ----------

    private fun seat(stack: Long, contributedThisStreet: Long): Seat = Seat(
        index = 0,
        playerId = "p0",
        displayName = "Hero",
        stack = stack,
        seatStatus = SeatStatus.Active,
        handParticipation = HandParticipation.InHand,
        contributedThisStreet = contributedThisStreet,
    )

    private fun state(
        currentBet: Long,
        lastFullRaise: Long,
        seats: List<Seat> = listOf(seat(stack = 1_000, contributedThisStreet = 0)),
        pots: List<Pot> = emptyList(),
    ): GameState = GameState(
        settings = settings,
        handNumber = 1,
        buttonSeatIndex = 0,
        seats = seats,
        community = emptyList(),
        street = BettingRound.Flop,
        currentBetThisStreet = currentBet,
        lastFullRaiseSize = lastFullRaise,
        actingSeatIndex = 0,
        deckRemaining = emptyList(),
        pots = pots,
    )
}
