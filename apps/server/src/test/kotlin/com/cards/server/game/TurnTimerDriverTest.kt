package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.server.domain.BotSeat
import com.dangerfield.cards.libraries.bots.BotDifficulty
import com.dangerfield.cards.libraries.bots.BotPersonality
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the server-side turn-timer driver: a human who sits on their action past
 * the per-turn limit is auto-folded (facing a bet) or auto-checked (when checking
 * is legal), while bot seats are left to the bot driver and a player who acts in
 * time is never double-acted.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TurnTimerDriverTest {

    private val settings = RoomSettings(
        smallBlind = 5,
        bigBlind = 10,
        startingStack = 1_000,
        maxSeats = 6,
        turnTimerSeconds = 30,
    )
    private val timeoutMs = settings.turnTimerSeconds.toLong() * 1_000L

    private val human0 = SeatOccupant(seatIndex = 0, userId = "human-1", displayName = "You", isBot = false)
    private val human1 = SeatOccupant(seatIndex = 1, userId = "human-2", displayName = "Peer", isBot = false)
    private val bot = SeatOccupant(
        seatIndex = 1,
        userId = "bot-1",
        displayName = "Jane",
        isBot = true,
        avatarEmoji = "🤖",
        bot = BotSeat(BotPersonality.Jane, BotDifficulty.Standard, revealed = true),
    )

    @Test
    fun timer_autoFolds_humanFacingBet_afterTimeout() = runTest {
        val session = GameSession(random = Random(seed = 11))
        TurnTimerDriver(session = session, scope = backgroundScope).start()

        session.startHand(listOf(human0, human1), settings)
        runCurrent()
        // Heads-up: the button (seat 0) is the small blind and is first to act,
        // facing the big blind — it cannot check.
        assertEquals(0, session.state.value!!.actingSeatIndex)

        advanceTimeBy(timeoutMs + 1)
        runCurrent()

        val state = session.state.value!!
        assertEquals(
            HandParticipation.Folded,
            state.seats.first { it.index == 0 }.handParticipation,
            "a stalling seat facing a bet is auto-folded",
        )
        assertEquals(BettingRound.Complete, state.street, "the heads-up fold ends the hand")
    }

    @Test
    fun timer_autoChecks_humanWhoStalls_whenCheckIsLegal() = runTest {
        val session = GameSession(random = Random(seed = 12))
        TurnTimerDriver(session = session, scope = backgroundScope).start()

        session.startHand(listOf(human0, human1), settings)
        runCurrent()
        // Seat 0 calls so the big blind (seat 1) faces no outstanding bet.
        session.applyIntent("human-1", PlayerIntent.Call(0), "call-0")
        runCurrent()
        assertEquals(1, session.state.value!!.actingSeatIndex)

        advanceTimeBy(timeoutMs + 1)
        runCurrent()

        val state = session.state.value!!
        assertTrue(
            state.seats.first { it.index == 1 }.handParticipation == HandParticipation.InHand,
            "a seat that can check is auto-checked, not folded",
        )
        assertEquals(BettingRound.Flop, state.street, "the auto-check completes preflop and the board advances")
    }

    @Test
    fun timer_leavesBotSeatsAlone() = runTest {
        val session = GameSession(random = Random(seed = 13))
        TurnTimerDriver(session = session, scope = backgroundScope).start()

        session.startHand(listOf(human0, bot), settings)
        runCurrent()
        // Hand control to the bot seat. No bot driver is running here, so if the
        // timer wrongly enforced on bots it would fold seat 1.
        session.applyIntent("human-1", PlayerIntent.Call(0), "call-0")
        runCurrent()
        assertEquals(1, session.state.value!!.actingSeatIndex)

        advanceTimeBy(timeoutMs + 1)
        runCurrent()

        val state = session.state.value!!
        assertEquals(1, state.actingSeatIndex, "the bot seat stays on the clock — the timer never acts for it")
        assertTrue(
            state.seats.first { it.index == 1 }.handParticipation == HandParticipation.InHand,
            "the bot is not folded by the human turn timer",
        )
    }

    @Test
    fun timer_doesNotFire_whenPlayerActsInTime() = runTest {
        val session = GameSession(random = Random(seed = 14))
        TurnTimerDriver(session = session, scope = backgroundScope).start()

        session.startHand(listOf(human0, human1), settings)
        runCurrent()
        // Seat 0 acts well before the deadline.
        advanceTimeBy(timeoutMs / 2)
        session.applyIntent("human-1", PlayerIntent.Fold(0), "fold-0")
        runCurrent()
        // Let any stale timer that should have been cancelled run past its window.
        advanceUntilIdle()

        val state = session.state.value!!
        // Seat 0 folded by its own action ending the heads-up hand; the assertion
        // is simply that the run settled cleanly with no exception from a
        // double-apply and the hand is complete.
        assertEquals(BettingRound.Complete, state.street)
        assertEquals(HandParticipation.Folded, state.seats.first { it.index == 0 }.handParticipation)
    }

    @Test
    fun timer_firesAgain_forSameSeatTwiceInOneStreet() = runTest {
        // Regression: the timeout nonce used to be hand+seat+street only, so a
        // seat that acted twice in one street (auto-checked, then faced a bet)
        // produced the SAME nonce the second time — the dedup ring swallowed it
        // and the table stalled. The nonce now includes the engine sequence.
        val session = GameSession(random = Random(seed = 21))
        TurnTimerDriver(session = session, scope = backgroundScope).start()

        session.startHand(listOf(human0, human1), settings)
        runCurrent()
        // Reach the flop: SB completes, BB checks.
        session.applyIntent("human-1", PlayerIntent.Call(0), "call-0")
        runCurrent()
        session.applyIntent("human-2", PlayerIntent.Check(1), "check-1")
        runCurrent()
        assertEquals(BettingRound.Flop, session.state.value!!.street)
        assertEquals(1, session.state.value!!.actingSeatIndex, "BB acts first postflop")

        // Decision A: seat 1 stalls → auto-checks, handing action to seat 0.
        advanceTimeBy(timeoutMs + 1)
        runCurrent()
        assertEquals(0, session.state.value!!.actingSeatIndex)

        // Seat 0 bets, putting seat 1 back on the clock IN THE SAME STREET.
        session.applyIntent("human-1", PlayerIntent.Bet(0, 50), "bet-flop")
        runCurrent()
        assertEquals(1, session.state.value!!.actingSeatIndex)

        // Decision B: seat 1 stalls again. The second timeout must fire (distinct
        // nonce) rather than being swallowed as a duplicate.
        advanceTimeBy(timeoutMs + 1)
        runCurrent()

        val state = session.state.value!!
        assertEquals(
            HandParticipation.Folded,
            state.seats.first { it.index == 1 }.handParticipation,
            "the second same-street timeout fires and folds the stalling seat",
        )
        assertEquals(BettingRound.Complete, state.street, "the fold ends the heads-up hand")
    }
}
