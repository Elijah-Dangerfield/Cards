package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.bots.BotDifficulty
import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.libraries.bots.BotThought
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.server.domain.BotSeat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
            thinkDelay = { _, _, _ -> 0 },
            mixedNextHandDelayMs = 0,
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
    fun serverThinkDelay_isVariable_acrossIdenticalSpots() {
        val thought = BotThought(
            handStrength = 0.5,
            potOdds = 0.4,
            drawProfile = null,
            opponentNote = null,
            rationale = "",
        )
        val random = Random(seed = 99)
        val samples = (0 until 200).map {
            serverThinkDelayMs(BotPersonality.Jane, thought, random)
        }

        // Same spot, same personality — yet the timing spreads, including the
        // occasional snap and tank, so the bot never reads as a metronome.
        assertTrue(samples.toSet().size > 50, "delays should vary decision-to-decision")
        assertTrue(samples.any { it < 600 }, "some decisions snap")
        assertTrue(samples.any { it > 2_500 }, "some decisions tank")
        assertTrue(samples.all { it in 300..6_000 }, "delays stay within sane bounds")
    }

    @Test
    fun driver_noBots_takesNoActions() = runTest {
        val session = GameSession(random = Random(seed = 1))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val driver = ServerBotDriver(
            session = session,
            scope = backgroundScope,
            cpuDispatcher = dispatcher,
            thinkDelay = { _, _, _ -> 0 },
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

    /**
     * CARDS-16 regression. A `1 human + N bots` table used to freeze at hand end:
     * the all-bot advance gate skipped it and the lone human stopped tapping
     * "next hand". The driver now advances any bot-occupied table, so consecutive
     * hands deal without a single human next-hand tap.
     */
    @Test
    fun mixedTable_autoAdvancesConsecutiveHands_withoutHumanNextHandTap() = runTest {
        val session = GameSession(random = Random(seed = 7))
        val driver = unconfinedDriver(session)
        driver.updateRoster(listOf(human, bot))
        driver.start()

        session.startHand(listOf(human, bot), settings)

        // Play the human's in-hand turns only — never call requestNextHand. The
        // bot driver must carry the table across hand boundaries on its own.
        val humanId = "human-1"
        var guard = 0
        while (guard++ < 300 && session.state.value!!.handNumber < 3) {
            advanceUntilIdle()
            val state = session.state.value!!
            if (state.handNumber >= 3) break
            if (state.street == BettingRound.Complete) continue // driver auto-advances
            val acting = state.actingSeatIndex ?: continue
            val seat = state.seats.first { it.index == acting }
            if (seat.playerId != humanId) continue // bot's turn — the driver acts
            val toCall = state.currentBetThisStreet - seat.contributedThisStreet
            val intent = if (toCall > 0) PlayerIntent.Fold(acting) else PlayerIntent.Check(acting)
            session.applyIntent(humanId, intent, "human-$guard")
        }

        assertTrue(
            session.state.value!!.handNumber >= 3,
            "a bot-occupied table advances consecutive hands with no human next-hand tap",
        )
    }

    /**
     * The flip side of CARDS-16: a pure all-human table must NOT auto-advance —
     * those players continue at their own pace via client "next hand" taps. The
     * driver only advances tables that contain a bot.
     */
    @Test
    fun allHumanTable_doesNotAutoAdvance_atHandEnd() = runTest {
        val session = GameSession(random = Random(seed = 3))
        val driver = unconfinedDriver(session)
        driver.start()

        val human2 = SeatOccupant(seatIndex = 1, userId = "human-2", displayName = "Peer", isBot = false)
        session.startHand(listOf(human, human2), settings)

        // Play hand 1 to completion with both humans acting.
        var guard = 0
        while (guard++ < 100 && session.state.value!!.street != BettingRound.Complete) {
            advanceUntilIdle()
            val state = session.state.value!!
            if (state.street == BettingRound.Complete) break
            val acting = state.actingSeatIndex ?: continue
            val seat = state.seats.first { it.index == acting }
            val toCall = state.currentBetThisStreet - seat.contributedThisStreet
            val intent = if (toCall > 0) PlayerIntent.Fold(acting) else PlayerIntent.Check(acting)
            session.applyIntent(seat.playerId!!, intent, "h-$guard")
        }

        assertEquals(BettingRound.Complete, session.state.value!!.street, "hand 1 reaches completion")
        val handAtComplete = session.state.value!!.handNumber

        // Let the scheduler drain fully — with no bot at the table nothing advances.
        advanceUntilIdle()

        assertEquals(
            handAtComplete,
            session.state.value!!.handNumber,
            "an all-human table never auto-advances; it waits for a client next-hand tap",
        )
        assertEquals(BettingRound.Complete, session.state.value!!.street)
    }

    /**
     * The teardown half: an all-bot table (no human left — the lone human quit a
     * fallback table, or every human dropped a private bot room) must NOT keep
     * simulating hands to an empty room. It goes idle at hand end so the orphan
     * sweep can reclaim it; no infinite equity-sim CPU burn.
     */
    @Test
    fun allBotTable_goesIdle_doesNotAdvance() = runTest {
        val session = GameSession(random = Random(seed = 11))
        val botA = SeatOccupant(seatIndex = 0, userId = "bot-1", displayName = "Jane", isBot = true,
            bot = BotSeat(BotPersonality.Jane, BotDifficulty.Standard, revealed = true))
        val botB = SeatOccupant(seatIndex = 1, userId = "bot-2", displayName = "David", isBot = true,
            bot = BotSeat(BotPersonality.David, BotDifficulty.Standard, revealed = true))

        // Reach a completed hand deterministically (one bot folds preflop) BEFORE
        // attaching the driver — so we isolate the advance decision from self-play.
        session.startHand(listOf(botA, botB), settings)
        val acting = session.state.value!!.actingSeatIndex!!
        val actorId = session.state.value!!.seats.first { it.index == acting }.playerId!!
        session.applyIntent(actorId, PlayerIntent.Fold(acting), "fold-1")
        assertEquals(BettingRound.Complete, session.state.value!!.street, "the fold ends hand 1")
        val handAtComplete = session.state.value!!.handNumber

        // Now attach the driver to the already-complete all-bot table.
        val driver = unconfinedDriver(session)
        driver.updateRoster(listOf(botA, botB))
        driver.start()
        advanceUntilIdle()

        assertEquals(
            handAtComplete,
            session.state.value!!.handNumber,
            "an all-bot table stays idle — never simulates a hand to an empty room",
        )
        assertEquals(BettingRound.Complete, session.state.value!!.street)
    }

    /**
     * Build a driver whose collector runs on an [UnconfinedTestDispatcher] so the
     * `collectLatest` loop actually drives state changes under `advanceUntilIdle`.
     * A `StandardTestDispatcher`-backed `backgroundScope` doesn't reliably pump the
     * hot-flow collector, which is fine for the snappy fold-immediately tests above
     * but hides the cross-hand advance these tests exercise. Delays are zeroed so
     * the table advances without burning virtual time.
     */
    private fun TestScope.unconfinedDriver(session: GameSession): ServerBotDriver =
        ServerBotDriver(
            session = session,
            scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            cpuDispatcher = UnconfinedTestDispatcher(testScheduler),
            random = Random(seed = 7),
            equityIterations = 20,
            thinkDelay = { _, _, _ -> 0 },
            mixedNextHandDelayMs = 0,
        )
}
