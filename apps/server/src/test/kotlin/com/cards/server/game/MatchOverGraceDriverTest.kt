package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.gameplay.SeatStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Pins the heads-up match-over resolution (MP-14): a hand that ends with one
 * player busted and the other holding chips must resolve to a terminal
 * match-over after the rebuy-grace window — and a rebuy inside the window must
 * cancel it. The bug this guards against was the table sitting on
 * "waiting for opponent to rebuy or leave" forever, with no test exercising what
 * happens when a player busts.
 */
@OptIn(ExperimentalTime::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MatchOverGraceDriverTest {

    private val settings = RoomSettings(
        smallBlind = 5,
        bigBlind = 10,
        startingStack = 1_000,
        maxSeats = 6,
        turnTimerSeconds = 30,
    )

    @Test
    fun bustedHeadsUp_resolvesToMatchOver_afterGrace() = runTest {
        val session = GameSession()
        val events = collectMatchOverEvents(session)

        startDriver(session)
        session.hydrate(headsUpBust())
        runCurrent()
        assertTrue(
            events.any { it is MatchOverEvent.GraceStarted },
            "a heads-up bust opens the rebuy-grace countdown",
        )

        advanceTimeBy(GRACE_MS + 1)
        runCurrent()
        val resolved = events.filterIsInstance<MatchOverEvent.Resolved>().singleOrNull()
        assertEquals("bob", resolved?.winnerUserId, "grace expiring crowns the surviving player")
    }

    @Test
    fun rebuyDuringGrace_cancelsTheMatchOver() = runTest {
        val session = GameSession()
        val events = collectMatchOverEvents(session)

        startDriver(session)
        session.hydrate(headsUpBust())
        runCurrent()
        assertTrue(events.any { it is MatchOverEvent.GraceStarted })

        // Busted player rebuys before grace expires — both seats hold chips again.
        session.hydrate(headsUpBust(bustedStack = settings.startingStack))
        advanceTimeBy(GRACE_MS + 1)
        runCurrent()

        assertTrue(events.any { it is MatchOverEvent.GraceCancelled }, "a rebuy clears the countdown")
        assertTrue(
            events.none { it is MatchOverEvent.Resolved },
            "the match never resolves once a rebuy lands inside the window",
        )
    }

    private fun kotlinx.coroutines.test.TestScope.collectMatchOverEvents(
        session: GameSession,
    ): List<MatchOverEvent> {
        val events = mutableListOf<MatchOverEvent>()
        backgroundScope.launch { session.matchOverEvents.collect { events += it } }
        runCurrent()
        return events
    }

    private fun kotlinx.coroutines.test.TestScope.startDriver(session: GameSession) {
        MatchOverGraceDriver(
            session = session,
            scope = backgroundScope,
            clock = Clock.System,
            graceMillis = GRACE_MS,
        ).start()
        runCurrent()
    }

    /** A hand-Complete heads-up state where seat 0 is busted to [bustedStack] and seat 1 (bob) holds chips. */
    private fun headsUpBust(bustedStack: Long = 0): GameState = GameState(
        settings = settings,
        handNumber = 3,
        buttonSeatIndex = 0,
        seats = listOf(
            Seat(
                index = 0,
                playerId = "alice",
                displayName = "Alice",
                stack = bustedStack,
                seatStatus = SeatStatus.Active,
                handParticipation = HandParticipation.Folded,
            ),
            Seat(
                index = 1,
                playerId = "bob",
                displayName = "Bob",
                stack = 2_000,
                seatStatus = SeatStatus.Active,
                handParticipation = HandParticipation.InHand,
            ),
        ),
        community = emptyList(),
        street = BettingRound.Complete,
        currentBetThisStreet = 0,
        lastFullRaiseSize = 0,
        actingSeatIndex = null,
        deckRemaining = emptyList(),
    )

    private companion object {
        const val GRACE_MS = 1_000L
    }
}
