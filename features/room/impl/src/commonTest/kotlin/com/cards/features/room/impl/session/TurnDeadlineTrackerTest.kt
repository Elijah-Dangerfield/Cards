package com.dangerfield.cards.features.room.impl.session

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.gameplay.SeatStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class TurnDeadlineTrackerTest {

    private class AdvancingClock(private var ms: Long) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(ms)
        fun advance(by: Long) { ms += by }
    }

    @Test
    fun stampsDeadline_fromClockPlusTimer_whenHumanOnTheClock() {
        val clock = AdvancingClock(1_000_000L)
        val tracker = TurnDeadlineTracker(clock)

        val deadline = tracker.deadlineFor(stateOn(lastSequence = 3, timerSeconds = 45))

        assertEquals(1_000_000L + 45_000L, deadline)
    }

    @Test
    fun sameTurn_keepsTheSameDeadline_evenAsTheClockAdvances() {
        // The MP-33 guard: re-projecting the same turn (e.g. the play screen
        // re-enters composition after the stats round-trip, or a non-action
        // snapshot lands) must NOT re-stamp the deadline — otherwise the ring
        // restarts from full.
        val clock = AdvancingClock(1_000_000L)
        val tracker = TurnDeadlineTracker(clock)

        val first = tracker.deadlineFor(stateOn(handNumber = 2, lastSequence = 7))
        clock.advance(10_000L)
        val second = tracker.deadlineFor(stateOn(handNumber = 2, lastSequence = 7))

        assertEquals(first, second, "the deadline for one turn is stamped once and reused")
    }

    @Test
    fun newTurn_readvancesTheDeadline() {
        val clock = AdvancingClock(1_000_000L)
        val tracker = TurnDeadlineTracker(clock)

        val first = tracker.deadlineFor(stateOn(handNumber = 2, lastSequence = 7))
        clock.advance(10_000L)
        val next = tracker.deadlineFor(stateOn(handNumber = 2, lastSequence = 8))

        assertTrue(next != null && first != null && next > first, "action moving re-arms the deadline")
        assertEquals(1_010_000L + 30_000L, next)
    }

    @Test
    fun noDeadline_whenNoSeatIsOnTheClock() {
        val tracker = TurnDeadlineTracker(AdvancingClock(1_000_000L))
        assertNull(tracker.deadlineFor(stateOn(actingSeatIndex = null)))
    }

    @Test
    fun noDeadline_whenABotIsOnTheClock() {
        val tracker = TurnDeadlineTracker(AdvancingClock(1_000_000L))
        assertNull(tracker.deadlineFor(stateOn(actingIsBot = true)), "bots aren't on a visible clock")
    }

    private fun stateOn(
        handNumber: Int = 1,
        lastSequence: Long = 0,
        actingSeatIndex: Int? = 0,
        actingIsBot: Boolean = false,
        timerSeconds: Int = 30,
    ): GameState = GameState(
        settings = RoomSettings.Default.copy(turnTimerSeconds = timerSeconds),
        handNumber = handNumber,
        buttonSeatIndex = 0,
        seats = listOf(
            Seat(
                index = 0,
                playerId = "p0",
                displayName = "Seat0",
                stack = 1_000,
                seatStatus = SeatStatus.Active,
                handParticipation = HandParticipation.InHand,
                isBot = actingIsBot,
            ),
            Seat(
                index = 1,
                playerId = "p1",
                displayName = "Seat1",
                stack = 1_000,
                seatStatus = SeatStatus.Active,
                handParticipation = HandParticipation.InHand,
            ),
        ),
        community = emptyList(),
        street = BettingRound.Preflop,
        currentBetThisStreet = 0,
        lastFullRaiseSize = 0,
        actingSeatIndex = actingSeatIndex,
        deckRemaining = emptyList(),
        lastSequence = lastSequence,
    )
}
