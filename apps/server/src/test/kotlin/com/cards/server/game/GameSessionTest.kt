package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.server.domain.HandOutcome
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue


/**
 * Server-side session-wrapper tests. Engine correctness (deal, betting
 * round advancement, showdown) is covered exhaustively by GameEngine's
 * own suite in :libraries:gameplay — these tests focus on the
 * session's contract: mutex-guarded mutation, nonce dedupe, actor
 * resolution by userId, illegal-intent rejection.
 */
class GameSessionTest {

    private val settings = RoomSettings(
        smallBlind = 5,
        bigBlind = 10,
        startingStack = 1_000,
        maxSeats = 6,
        turnTimerSeconds = 30,
    )

    private val alice = SeatOccupant(seatIndex = 0, userId = "alice", displayName = "Alice", isBot = false)
    private val bob = SeatOccupant(seatIndex = 1, userId = "bob", displayName = "Bob", isBot = false)

    private fun newSession() = GameSession(random = Random(seed = 42))

    private val carol = SeatOccupant(seatIndex = 2, userId = "carol", displayName = "Carol", isBot = false)

    private fun GameSession.actingUserId(): String =
        state.value!!.let { s -> s.seats.first { it.index == s.actingSeatIndex }.playerId!! }

    // ---------- mid-hand join (seat-at-next-hand queue) ----------

    @Test
    fun queuedJoiner_isDealtIn_atTheNextHand() = runTest {
        val session = newSession()
        session.startHand(listOf(alice, bob), settings)
        runCurrent()
        // Carol joins mid-hand — queued, not in the current hand's seats.
        session.queueJoiner(carol)
        runCurrent()
        assertFalse(
            session.state.value!!.seats.any { it.playerId == "carol" },
            "a mid-hand joiner spectates the current hand, not seated yet",
        )

        // End the hand (heads-up forfeit → Complete), then deal the next.
        session.forfeitSeat(session.actingUserId())
        runCurrent()
        assertEquals(BettingRound.Complete, session.state.value!!.street)
        val next = session.requestNextHand(actorUserId = "alice", clientNonce = "n1")
        runCurrent()

        assertIs<IntentResult.Accepted>(next)
        assertEquals(
            setOf("alice", "bob", "carol"),
            session.state.value!!.seats.mapNotNull { it.playerId }.toSet(),
            "the queued joiner is dealt into the next hand",
        )
    }

    @Test
    fun dequeuedJoiner_isNotDealtIn() = runTest {
        val session = newSession()
        session.startHand(listOf(alice, bob), settings)
        runCurrent()
        session.queueJoiner(carol)
        session.dequeueJoiner("carol") // left before the next hand
        runCurrent()

        session.forfeitSeat(session.actingUserId())
        runCurrent()
        session.requestNextHand(actorUserId = "alice", clientNonce = "n1")
        runCurrent()

        assertFalse(
            session.state.value!!.seats.any { it.playerId == "carol" },
            "a joiner who left before the boundary is never dealt in",
        )
    }

    @Test
    fun queuedJoiner_isClearedAfterBeingDealt_notReSeatedTheHandAfter() = runTest {
        val session = newSession()
        session.startHand(listOf(alice, bob), settings)
        runCurrent()
        session.queueJoiner(carol)

        session.forfeitSeat(session.actingUserId())
        runCurrent()
        session.requestNextHand(actorUserId = "alice", clientNonce = "n1") // carol dealt + queue cleared
        runCurrent()
        // Play this hand to Complete and advance again — carol shouldn't be
        // re-added from a stale queue (she's already a real seat now).
        session.forfeitSeat(session.actingUserId())
        runCurrent()
        val before = session.state.value!!.seats.count { it.playerId == "carol" }
        session.requestNextHand(actorUserId = "alice", clientNonce = "n2")
        runCurrent()

        assertEquals(1, before, "carol is one seat after being dealt")
        assertEquals(
            1,
            session.state.value!!.seats.count { it.playerId == "carol" },
            "the queue was cleared — carol isn't duplicated the hand after",
        )
    }

    // ---------- forfeitSeat (mid-hand leave) ----------

    @Test
    fun forfeitActingSeat_threeHanded_advancesAction_andContinues() = runTest {
        val session = newSession()
        session.startHand(listOf(alice, bob, carol), settings)
        runCurrent()
        val actingIdx = session.state.value!!.actingSeatIndex!!
        val actingUser = session.actingUserId()

        val result = session.forfeitSeat(actingUser)
        runCurrent()

        assertIs<IntentResult.Accepted>(result)
        val state = session.state.value!!
        assertEquals(
            HandParticipation.Folded,
            state.seats.first { it.index == actingIdx }.handParticipation,
        )
        assertTrue(state.street != BettingRound.Complete, "two contenders remain → hand continues")
        assertTrue(
            state.actingSeatIndex != null && state.actingSeatIndex != actingIdx,
            "action moved off the gone seat (no stall)",
        )
    }

    @Test
    fun forfeitActingSeat_headsUp_endsHand() = runTest {
        val session = newSession()
        session.startHand(listOf(alice, bob), settings)
        runCurrent()

        session.forfeitSeat(session.actingUserId())
        runCurrent()

        assertEquals(BettingRound.Complete, session.state.value!!.street, "one contender left → hand over")
    }

    @Test
    fun forfeit_unknownUser_isNoOpAccepted() = runTest {
        val session = newSession()
        session.startHand(listOf(alice, bob), settings)
        runCurrent()
        val before = session.state.value

        val result = session.forfeitSeat("ghost")

        assertIs<IntentResult.Accepted>(result)
        assertEquals(before, session.state.value, "no state change for an unseated user")
    }

    @Test
    fun forfeit_isIdempotent_andNoOpAfterComplete() = runTest {
        val session = newSession()
        session.startHand(listOf(alice, bob), settings)
        runCurrent()
        val user = session.actingUserId()
        session.forfeitSeat(user) // ends the heads-up hand
        runCurrent()
        val afterFirst = session.state.value

        val again = session.forfeitSeat(user)

        assertIs<IntentResult.Accepted>(again)
        assertEquals(afterFirst, session.state.value, "a repeat / post-complete forfeit changes nothing")
    }

    @Test
    fun startHand_revealedBotOccupant_seatCarriesStyleKey_hiddenDoesNot() = runTest {
        val session = newSession()
        val revealed = SeatOccupant(
            seatIndex = 1,
            userId = "bot-r",
            displayName = "Jane",
            isBot = true,
            bot = com.dangerfield.cards.server.domain.BotSeat(
                com.dangerfield.cards.libraries.bots.BotPersonality.Jane,
                com.dangerfield.cards.libraries.bots.BotDifficulty.Standard,
                revealed = true,
            ),
        )
        session.startHand(listOf(alice, revealed), settings)

        val botSeat = session.state.value!!.seats.first { it.playerId == "bot-r" }
        assertEquals("Jane", botSeat.botStyleKey, "a revealed bot publishes its personality name")
        assertTrue(botSeat.isBot)
        val human = session.state.value!!.seats.first { it.playerId == "alice" }
        assertNull(human.botStyleKey)
    }

    @Test
    fun startHand_seedsState_andEmitsOpeningEvents() = runTest {
        val session = newSession()

        val result = session.startHand(listOf(alice, bob), settings)

        assertIs<IntentResult.Accepted>(result)
        val state = session.state.value
        assertNotNull(state)
        assertEquals(1, state.handNumber)
        assertEquals(2, state.seats.size)
        assertEquals("alice", state.seats[0].playerId)
        assertEquals("bob", state.seats[1].playerId)
        assertEquals(1_000L, state.seats[0].stack + state.seats[0].contributedThisStreet)
        // It's preflop; someone is acting.
        assertEquals(BettingRound.Preflop, state.street)
        assertNotNull(state.actingSeatIndex)
    }

    @Test
    fun startHand_secondCall_whileInProgress_isRejected() = runTest {
        val session = newSession()
        session.startHand(listOf(alice, bob), settings)

        val second = session.startHand(listOf(alice, bob), settings)

        assertIs<IntentResult.Rejected>(second)
        assertTrue(second.reason.contains("in progress"))
    }

    @Test
    fun startHand_requiresAtLeastTwoOccupants() = runTest {
        val session = newSession()

        val result = session.startHand(listOf(alice), settings)

        assertIs<IntentResult.Rejected>(result)
    }

    @Test
    fun applyIntent_beforeStart_isRejected() = runTest {
        val session = newSession()

        val result = session.applyIntent(
            actorUserId = "alice",
            intent = PlayerIntent.Fold(seatIndex = 0),
            clientNonce = "n1",
        )

        assertIs<IntentResult.Rejected>(result)
    }

    @Test
    fun applyIntent_unknownUserId_isRejected() = runTest {
        val session = newSession()
        session.startHand(listOf(alice, bob), settings)

        val result = session.applyIntent(
            actorUserId = "stranger",
            intent = PlayerIntent.Fold(seatIndex = 0),
            clientNonce = "n1",
        )

        assertIs<IntentResult.Rejected>(result)
        assertTrue(result.reason.contains("not seated"))
    }

    @Test
    fun applyIntent_outOfTurn_isRejected() = runTest {
        val session = newSession()
        session.startHand(listOf(alice, bob), settings)
        val nonActor = session.state.value!!.seats
            .first { it.index != session.state.value!!.actingSeatIndex }

        val result = session.applyIntent(
            actorUserId = nonActor.playerId!!,
            intent = PlayerIntent.Fold(seatIndex = nonActor.index),
            clientNonce = "n1",
        )

        assertIs<IntentResult.Rejected>(result)
        assertTrue(result.reason.contains("not your turn"))
    }

    @Test
    fun applyIntent_intentSeatMismatch_isRejected() = runTest {
        // Caller is Alice but the intent claims seat 1 (Bob's). Even
        // if it were Alice's turn the seat-mismatch guard fires —
        // protects against a client crafting an intent with someone
        // else's seatIndex.
        val session = newSession()
        session.startHand(listOf(alice, bob), settings)
        val acting = session.state.value!!.actingSeatIndex!!
        val actorUser = session.state.value!!.seats.first { it.index == acting }.playerId!!
        val wrongSeat = if (acting == 0) 1 else 0

        val result = session.applyIntent(
            actorUserId = actorUser,
            intent = PlayerIntent.Fold(seatIndex = wrongSeat),
            clientNonce = "n1",
        )

        assertIs<IntentResult.Rejected>(result)
    }

    @Test
    fun applyIntent_legalFold_advancesAndCompletesHand_heads_up() = runTest {
        // Heads-up: when one player folds, the hand ends and the pot
        // goes to the survivor. State.street goes to Complete.
        val session = newSession()
        session.startHand(listOf(alice, bob), settings)
        val acting = session.state.value!!.actingSeatIndex!!
        val actor = session.state.value!!.seats.first { it.index == acting }

        val result = session.applyIntent(
            actorUserId = actor.playerId!!,
            intent = PlayerIntent.Fold(seatIndex = acting),
            clientNonce = "n1",
        )

        assertIs<IntentResult.Accepted>(result)
        assertEquals(BettingRound.Complete, session.state.value!!.street)
    }

    @Test
    fun handCompletion_firesOnHandFinishedOnce_withHumanSeatsAndHandNumber() = runTest {
        val finished = mutableListOf<HandOutcome>()
        val session = GameSession(
            random = Random(seed = 42),
            onHandFinished = { outcome -> finished += outcome },
        )
        session.startHand(listOf(alice, bob), settings)
        val acting = session.state.value!!.actingSeatIndex!!
        val actor = session.state.value!!.seats.first { it.index == acting }

        session.applyIntent(actor.playerId!!, PlayerIntent.Fold(seatIndex = acting), "n1")

        assertEquals(1, finished.size, "fires exactly once on the completing action")
        assertEquals(setOf("alice", "bob"), finished.single().perHuman.keys)
        assertEquals(1, finished.single().handNumber, "carries the completed hand number")
    }

    @Test
    fun nonCompletingAction_doesNotFireOnHandFinished() = runTest {
        val finished = mutableListOf<HandOutcome>()
        val session = GameSession(
            random = Random(seed = 42),
            onHandFinished = { outcome -> finished += outcome },
        )
        session.startHand(listOf(alice, bob), settings)
        val acting = session.state.value!!.actingSeatIndex!!
        val actor = session.state.value!!.seats.first { it.index == acting }

        // Heads-up preflop: the small blind calling keeps the hand live
        // (the big blind still has the option), so no completion fires.
        val result = session.applyIntent(actor.playerId!!, PlayerIntent.Call(seatIndex = acting), "n1")

        assertIs<IntentResult.Accepted>(result)
        assertTrue(session.state.value!!.street != BettingRound.Complete)
        assertTrue(finished.isEmpty(), "the counter only fires when a hand actually finishes")
    }

    @Test
    fun onHandFinished_excludesBotSeats() = runTest {
        val bot = SeatOccupant(seatIndex = 1, userId = "bot-1", displayName = "Botty", isBot = true)
        val finished = mutableListOf<HandOutcome>()
        val session = GameSession(
            random = Random(seed = 42),
            onHandFinished = { outcome -> finished += outcome },
        )
        session.startHand(listOf(alice, bot), settings)
        val acting = session.state.value!!.actingSeatIndex!!
        val actor = session.state.value!!.seats.first { it.index == acting }

        session.applyIntent(actor.playerId!!, PlayerIntent.Fold(seatIndex = acting), "n1")

        assertEquals(1, finished.size)
        assertEquals(setOf("alice"), finished.single().perHuman.keys, "bots don't carry a server-witnessed count")
    }

    @Test
    fun onHandFinished_foldWinner_carriesPotAndWinFlag() = runTest {
        val finished = mutableListOf<HandOutcome>()
        val session = GameSession(
            random = Random(seed = 42),
            onHandFinished = { outcome -> finished += outcome },
        )
        session.startHand(listOf(alice, bob), settings)
        val acting = session.state.value!!.actingSeatIndex!!
        val folder = session.state.value!!.seats.first { it.index == acting }
        val winnerId = session.state.value!!.seats.first { it.index != acting }.playerId!!

        session.applyIntent(folder.playerId!!, PlayerIntent.Fold(seatIndex = acting), "n1")

        val outcome = finished.single()
        // Heads-up SB folds preflop: pot is SB + BB = 15. Winner nets the
        // small blind; the folder loses theirs.
        assertEquals(15L, outcome.perHuman.values.first().potTotal)
        assertTrue(outcome.perHuman.getValue(winnerId).won, "the seat that took the blinds won the hand")
        assertTrue(!outcome.perHuman.getValue(folder.playerId!!).won, "the folder did not win")
    }

    @Test
    fun applyIntent_dedupesByNonce() = runTest {
        val session = newSession()
        session.startHand(listOf(alice, bob), settings)
        val acting = session.state.value!!.actingSeatIndex!!
        val actor = session.state.value!!.seats.first { it.index == acting }

        val first = session.applyIntent(
            actorUserId = actor.playerId!!,
            intent = PlayerIntent.Fold(seatIndex = acting),
            clientNonce = "dup",
        )
        // Hand is now Complete. Resubmit same nonce — should be a
        // silent Accepted, NOT a "no active hand" or "current hand not
        // complete" reject. Dedupe wins.
        val second = session.applyIntent(
            actorUserId = actor.playerId!!,
            intent = PlayerIntent.Fold(seatIndex = acting),
            clientNonce = "dup",
        )

        assertIs<IntentResult.Accepted>(first)
        assertIs<IntentResult.Accepted>(second)
    }

    @Test
    fun requestNextHand_beforeCompletion_isRejected() = runTest {
        val session = newSession()
        session.startHand(listOf(alice, bob), settings)

        val result = session.requestNextHand(actorUserId = "alice", clientNonce = "n1")

        assertIs<IntentResult.Rejected>(result)
        assertTrue(result.reason.contains("not complete"))
    }

    @Test
    fun requestNextHand_afterCompletion_rotatesButton_carriesStacks() = runTest {
        val session = newSession()
        session.startHand(listOf(alice, bob), settings)
        val firstButton = session.state.value!!.buttonSeatIndex
        val firstHandStateSeats = session.state.value!!.seats

        // Drive to completion by folding the actor.
        val acting = session.state.value!!.actingSeatIndex!!
        val actor = firstHandStateSeats.first { it.index == acting }
        session.applyIntent(actor.playerId!!, PlayerIntent.Fold(seatIndex = acting), "n1")
        assertEquals(BettingRound.Complete, session.state.value!!.street)

        // Capture post-hand stacks so we can compare carry-over after
        // the next hand opens (and posts blinds).
        val postHandStacks = session.state.value!!.seats.associate { it.index to it.stack }

        val result = session.requestNextHand(actorUserId = "alice", clientNonce = "n2")

        assertIs<IntentResult.Accepted>(result)
        val newState = session.state.value!!
        assertEquals(2, newState.handNumber)
        assertEquals(BettingRound.Preflop, newState.street)
        // Button rotated to the next seat (heads-up: 0 → 1 → 0 → ...).
        val expectedNewButton = if (firstButton == 0) 1 else 0
        assertEquals(expectedNewButton, newState.buttonSeatIndex)
        // Stacks carry over within bigBlind tolerance — blinds for the
        // new hand have been posted, so stacks are reduced by sb/bb.
        for (seat in newState.seats) {
            val priorStack = postHandStacks[seat.index] ?: continue
            val deducted = priorStack - seat.stack
            assertTrue(deducted in 0..settings.bigBlind, "Seat ${seat.index} deducted $deducted from $priorStack")
        }
    }

    @Test
    fun startHand_ridesOccupantXpOntoSeats() = runTest {
        val session = newSession()

        session.startHand(
            listOf(alice.copy(xp = 2_500), bob.copy(xp = null)),
            settings,
        )

        val state = session.state.value!!
        assertEquals(2_500L, state.seats.first { it.playerId == "alice" }.xp)
        assertNull(state.seats.first { it.playerId == "bob" }.xp)
    }

    @Test
    fun requestNextHand_preservesSeatXpAcrossHands() = runTest {
        val session = newSession()
        session.startHand(listOf(alice.copy(xp = 2_500), bob.copy(xp = 90)), settings)

        // Drive to completion by folding the actor, then open the next hand.
        val acting = session.state.value!!.actingSeatIndex!!
        val actor = session.state.value!!.seats.first { it.index == acting }
        session.applyIntent(actor.playerId!!, PlayerIntent.Fold(seatIndex = acting), "n1")
        assertEquals(BettingRound.Complete, session.state.value!!.street)

        session.requestNextHand(actorUserId = "alice", clientNonce = "n2")

        val newState = session.state.value!!
        assertEquals(2_500L, newState.seats.first { it.playerId == "alice" }.xp)
        assertEquals(90L, newState.seats.first { it.playerId == "bob" }.xp)
    }

    @Test
    fun stateFlow_isNull_beforeFirstHand() = runTest {
        val session = newSession()
        assertNull(session.state.value)
    }

    @Test
    fun emitEmoji_beforeStart_isRejected() = runTest {
        val session = newSession()

        val result = session.emitEmoji(actorUserId = "alice", emoji = "🎉")

        assertIs<IntentResult.Rejected>(result)
    }

    @Test
    fun emitEmoji_fromNonSeatedUser_isRejected() = runTest {
        val session = newSession()
        session.startHand(listOf(alice, bob), settings)

        val result = session.emitEmoji(actorUserId = "stranger", emoji = "🎉")

        assertIs<IntentResult.Rejected>(result)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun emitEmoji_fromSeatedUser_fansOutAttributedToTheirSeat() = runTest {
        val session = newSession()
        session.startHand(listOf(alice, bob), settings)
        val received = mutableListOf<SeatEmoji>()
        val collector = launch { session.emojiBlasts.collect { received += it } }
        runCurrent()

        val result = session.emitEmoji(actorUserId = "bob", emoji = "🔥")
        runCurrent()

        assertIs<IntentResult.Accepted>(result)
        assertEquals(SeatEmoji(seatIndex = 1, emoji = "🔥"), received.single())
        collector.cancel()
    }
}

