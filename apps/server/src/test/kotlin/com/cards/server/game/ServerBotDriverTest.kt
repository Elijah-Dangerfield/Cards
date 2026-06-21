package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.bots.BotDifficulty
import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.server.domain.BotSeat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the server-side bot driver: it acts only on bot seats, hands control
 * back to humans, and drives bot turns through to a finished hand. Engine
 * correctness lives in :libraries:gameplay; here we care about the driver's
 * orchestration contract over a real [GameSession].
 */
class ServerBotDriverTest {

    private val settings = RoomSettings(
        smallBlind = 5,
        bigBlind = 10,
        startingStack = 1_000,
        maxSeats = 6,
        turnTimerSeconds = 30,
    )

    private val human = SeatOccupant(seatIndex = 0, userId = "human-1", displayName = "You", isBot = false)
    private val bot = SeatOccupant(
        seatIndex = 1,
        userId = "bot-1",
        displayName = "Jane",
        isBot = true,
        avatarEmoji = "🤖",
        bot = BotSeat(BotPersonality.Jane, BotDifficulty.Standard, revealed = true),
    )

    @Test
    fun driver_playsBotTurns_handsControlBackToHuman_untilHandCompletes() = runTest {
        val session = GameSession(random = Random(seed = 7))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val driver = ServerBotDriver(
            session = session,
            scope = backgroundScope,
            cpuDispatcher = dispatcher,
            random = Random(seed = 7),
            equityIterations = 20,
            thinkDelay = { _, _ -> 0 },
            nextHandDelayMs = 0,
        )
        driver.updateRoster(listOf(human, bot))
        driver.start()

        session.startHand(listOf(human, bot), settings)

        // Drive the hand: after each settle the driver has played every
        // consecutive bot turn and stalled on the human (or finished the hand).
        var guard = 0
        while (guard++ < 50) {
            advanceUntilIdle()
            val state = session.state.value!!
            if (state.street == BettingRound.Complete) break
            val acting = state.actingSeatIndex
            assertNotNull(acting, "a non-complete hand always has an actor")
            assertEquals(0, acting, "driver must never leave a bot seat waiting — only the human should be on the clock")
            // Human folds when facing a bet, else checks — the simplest legal line.
            val seat = state.seats.first { it.index == 0 }
            val toCall = state.currentBetThisStreet - seat.contributedThisStreet
            val intent = if (toCall > 0) PlayerIntent.Fold(0) else PlayerIntent.Check(0)
            session.applyIntent("human-1", intent, "human-$guard")
        }

        assertEquals(BettingRound.Complete, session.state.value!!.street, "the hand reaches completion under the driver")
    }

    @Test
    fun driver_noBots_takesNoActions() = runTest {
        val session = GameSession(random = Random(seed = 1))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val driver = ServerBotDriver(
            session = session,
            scope = backgroundScope,
            cpuDispatcher = dispatcher,
            thinkDelay = { _, _ -> 0 },
        )
        driver.start()

        val other = SeatOccupant(seatIndex = 1, userId = "human-2", displayName = "Peer", isBot = false)
        session.startHand(listOf(human, other), settings)
        val actorBefore = session.state.value!!.actingSeatIndex
        advanceUntilIdle()

        // With no bot seats the driver is inert: the acting seat is untouched.
        assertEquals(actorBefore, session.state.value!!.actingSeatIndex)
        assertTrue(session.state.value!!.seats.all { !it.hasActedThisStreet })
    }
}
