package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.gameplay.SeatStatus
import com.dangerfield.cards.libraries.gameplay.GameState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TableUiStateTest {

    @Test
    fun allInWithZeroStackMidHand_isNotBusted() {
        val table = activeFromSeats(
            street = BettingRound.Flop,
            seats = listOf(
                seat(index = 0, stack = 1_000, participation = HandParticipation.InHand),
                seat(index = 1, stack = 0, participation = HandParticipation.AllIn),
            ),
        )
        val allIn = table.seats.single { it.index == 1 }
        assertFalse(allIn.isBusted, "AllIn with 0 stack mid-hand must not be busted")
    }

    @Test
    fun allInLoserAtComplete_isBusted() {
        val table = activeFromSeats(
            street = BettingRound.Complete,
            seats = listOf(
                seat(index = 0, stack = 2_000, participation = HandParticipation.InHand),
                seat(index = 1, stack = 0, participation = HandParticipation.AllIn),
            ),
        )
        val loser = table.seats.single { it.index == 1 }
        assertTrue(loser.isBusted, "AllIn loser at Complete should read as busted")
    }

    @Test
    fun zeroStackNotDealt_isBusted() {
        val table = activeFromSeats(
            street = BettingRound.Preflop,
            seats = listOf(
                seat(index = 0, stack = 1_000, participation = HandParticipation.InHand),
                seat(index = 1, stack = 0, participation = HandParticipation.NotDealt),
            ),
        )
        val sittingOut = table.seats.single { it.index == 1 }
        assertTrue(sittingOut.isBusted, "Sat-out (NotDealt) with 0 stack reads as busted between hands")
    }

    @Test
    fun emptySeat_isNotBusted() {
        val table = activeFromSeats(
            street = BettingRound.Complete,
            seats = listOf(
                seat(index = 0, stack = 1_000, participation = HandParticipation.InHand),
                seat(index = 1, stack = 0, participation = HandParticipation.NotDealt, empty = true),
            ),
        )
        val empty = table.seats.single { it.index == 1 }
        assertFalse(empty.isBusted, "Empty seats are not busted players")
    }

    private fun seat(
        index: Int,
        stack: Long,
        participation: HandParticipation,
        empty: Boolean = false,
    ): Seat = Seat(
        index = index,
        playerId = if (empty) null else "p$index",
        displayName = if (empty) "" else "Seat$index",
        stack = stack,
        seatStatus = if (empty) SeatStatus.Empty else SeatStatus.Active,
        handParticipation = participation,
    )

    private fun activeFromSeats(
        street: BettingRound,
        seats: List<Seat>,
    ): TableUiState.Active {
        val state = GameState(
            settings = RoomSettings.Default,
            handNumber = 1,
            buttonSeatIndex = 0,
            seats = seats,
            community = emptyList(),
            street = street,
            currentBetThisStreet = 0,
            lastFullRaiseSize = 0,
            actingSeatIndex = null,
            deckRemaining = emptyList(),
        )
        return TableUiState.fromGameState(
            gameState = state,
            humanSeatIndex = 0,
            personalitiesBySeat = emptyMap(),
            lastWinners = null,
            lastActionBySeat = emptyMap(),
        )
    }
}
