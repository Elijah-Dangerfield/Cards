package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val finished = mutableListOf<Pair<List<String>, Int>>()
        val session = GameSession(
            random = Random(seed = 42),
            onHandFinished = { ids, handNumber -> finished += ids to handNumber },
        )
        session.startHand(listOf(alice, bob), settings)
        val acting = session.state.value!!.actingSeatIndex!!
        val actor = session.state.value!!.seats.first { it.index == acting }

        session.applyIntent(actor.playerId!!, PlayerIntent.Fold(seatIndex = acting), "n1")

        assertEquals(1, finished.size, "fires exactly once on the completing action")
        assertEquals(setOf("alice", "bob"), finished.single().first.toSet())
        assertEquals(1, finished.single().second, "carries the completed hand number")
    }

    @Test
    fun nonCompletingAction_doesNotFireOnHandFinished() = runTest {
        val finished = mutableListOf<Pair<List<String>, Int>>()
        val session = GameSession(
            random = Random(seed = 42),
            onHandFinished = { ids, handNumber -> finished += ids to handNumber },
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
        val finished = mutableListOf<Pair<List<String>, Int>>()
        val session = GameSession(
            random = Random(seed = 42),
            onHandFinished = { ids, handNumber -> finished += ids to handNumber },
        )
        session.startHand(listOf(alice, bot), settings)
        val acting = session.state.value!!.actingSeatIndex!!
        val actor = session.state.value!!.seats.first { it.index == acting }

        session.applyIntent(actor.playerId!!, PlayerIntent.Fold(seatIndex = acting), "n1")

        assertEquals(1, finished.size)
        assertEquals(listOf("alice"), finished.single().first, "bots don't carry a server-witnessed count")
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
}

