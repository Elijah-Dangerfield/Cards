package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.HandWinner
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.gameplay.SeatStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure unit tests for [HandEndProgression] — the end-of-hand derivations
 * extracted from [PlayPokerViewModel]. No VM, repositories, or coroutines.
 */
class HandEndProgressionTest {

    // ---------- feedbackEvents ----------

    @Test
    fun winner_getsShowdownSound_andHandWonHaptic() {
        val state = state(seats = listOf(seat(0, stack = 2_000), seat(1, stack = 0)))
        val events = HandEndProgression.feedbackEvents(
            event = handEnded(winnerSeat = 0),
            state = state,
            humanSeatIndex = 0,
        )
        assertEquals(
            listOf(
                PlayPokerEvent.PlaySound(SoundKind.Showdown),
                PlayPokerEvent.PlayHaptic(HapticKind.HandWon),
            ),
            events,
        )
    }

    @Test
    fun loser_getsHandLostHaptic_noBustWhenChipsRemain() {
        val state = state(seats = listOf(seat(0, stack = 500), seat(1, stack = 1_500)))
        val events = HandEndProgression.feedbackEvents(
            event = handEnded(winnerSeat = 1),
            state = state,
            humanSeatIndex = 0,
        )
        assertEquals(
            listOf(
                PlayPokerEvent.PlaySound(SoundKind.Showdown),
                PlayPokerEvent.PlayHaptic(HapticKind.HandLost),
            ),
            events,
        )
    }

    @Test
    fun bustedHuman_alsoGetsBustHaptic() {
        val state = state(seats = listOf(seat(0, stack = 0), seat(1, stack = 2_000)))
        val events = HandEndProgression.feedbackEvents(
            event = handEnded(winnerSeat = 1),
            state = state,
            humanSeatIndex = 0,
        )
        assertEquals(
            listOf(
                PlayPokerEvent.PlaySound(SoundKind.Showdown),
                PlayPokerEvent.PlayHaptic(HapticKind.HandLost),
                PlayPokerEvent.PlayHaptic(HapticKind.Bust),
            ),
            events,
        )
    }

    @Test
    fun spectator_notSeated_getsNoFeedback() {
        val state = state(seats = listOf(seat(0, stack = 1_000), seat(1, stack = 1_000)))
        val events = HandEndProgression.feedbackEvents(
            event = handEnded(winnerSeat = 0),
            state = state,
            humanSeatIndex = 99, // not at the table
        )
        assertTrue(events.isEmpty(), "no outcome is attributed to a non-seated viewer")
    }

    // ---------- achievementContext ----------

    @Test
    fun achievementContext_capturesOpponentsStacksAndBusts() {
        val state = state(
            seats = listOf(
                seat(0, stack = 3_000, isBot = false, name = "You"),
                seat(1, stack = 0, isBot = true, name = "Steve"),
                seat(2, stack = 500, isBot = true, name = "Jane"),
            ),
        )
        val context = HandEndProgression.achievementContext(
            state = state,
            humanSeatIndex = 0,
            humanStartingStack = 1_000,
            difficultyName = "Casual",
        )

        assertEquals(listOf("Steve", "Jane"), context.opponentBotNames)
        assertEquals("Casual", context.botDifficulty)
        assertEquals(1_000, context.humanStartingStack)
        assertEquals(3_000, context.humanEndingStack)
        assertEquals(10, context.bigBlind)
        assertEquals(1, context.bustedOpponentCount, "one opponent (Steve) busted this hand")
    }

    // ---------- builders ----------

    private fun handEnded(winnerSeat: Int) = GameEvent.HandEnded(
        sequence = 9,
        winners = listOf(HandWinner(seatIndex = winnerSeat, amount = 100, handRank = null, byFold = false)),
        board = emptyList(),
        revealedHoleCards = emptyMap(),
    )

    private fun seat(
        index: Int,
        stack: Long,
        isBot: Boolean = false,
        name: String = "P$index",
    ): Seat = Seat(
        index = index,
        playerId = "p$index",
        displayName = name,
        stack = stack,
        seatStatus = SeatStatus.Active,
        handParticipation = HandParticipation.InHand,
        isBot = isBot,
    )

    private fun state(seats: List<Seat>): GameState = GameState(
        settings = RoomSettings(smallBlind = 5, bigBlind = 10, startingStack = 1_000, maxSeats = 6, turnTimerSeconds = 30),
        handNumber = 1,
        buttonSeatIndex = 0,
        seats = seats,
        community = emptyList(),
        street = BettingRound.Complete,
        currentBetThisStreet = 0,
        lastFullRaiseSize = 0,
        actingSeatIndex = null,
        deckRemaining = emptyList(),
    )
}
