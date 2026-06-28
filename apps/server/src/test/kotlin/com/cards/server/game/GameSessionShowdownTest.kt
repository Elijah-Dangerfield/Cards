package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.Deck
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.server.domain.HandOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Showdown completion path (MP-25). The existing [GameSessionTest] suite only
 * drives hands to completion via folds; this one scripts a deck so a heads-up
 * hand checks down to the river and resolves at **showdown**. It guards that the
 * showdown completion fires `onHandFinished` (and marks the win as a showdown,
 * not a fold) exactly like the fold path — the server side of the MP-25
 * "no showdown shown" report. The reveal that the reporter actually missed was a
 * client gap (see `TableUiStateTest.completeSnapshot_revealsInHandOpponentCards_*`);
 * this proves the server emits the resolution the client renders from. Compare
 * with the fold path in
 * [GameSessionTest.handCompletion_firesOnHandFinishedOnce_withHumanSeatsAndHandNumber].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameSessionShowdownTest {

    private val settings = RoomSettings(
        smallBlind = 5,
        bigBlind = 10,
        startingStack = 1_000,
        maxSeats = 6,
        turnTimerSeconds = 30,
    )

    private val alice = SeatOccupant(seatIndex = 0, userId = "alice", displayName = "Alice", isBot = false)
    private val bob = SeatOccupant(seatIndex = 1, userId = "bob", displayName = "Bob", isBot = false)

    private fun cards(spec: String): List<Card> =
        spec.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { Card.parse(it) }

    // Heads-up, scripted so seat 0 (Aces) beats seat 1 (Kings) on a dry board —
    // no pair on the felt, so the result rides on the hole cards alone.
    private fun showdownDeck(): Deck {
        val holeBySeat = listOf(
            cards("As Ad"),
            cards("Ks Kd"),
        )
        val board = cards("2c 7d 9h Th 3s")
        val front = holeBySeat.flatten() + board
        val rest = Card.fullDeck.filterNot { it in front.toSet() }
        return Deck.fromOrdered(front + rest)
    }

    private fun GameSession.actingUserId(): String =
        state.value!!.let { s -> s.seats.first { it.index == s.actingSeatIndex }.playerId!! }

    // Drive a heads-up hand from preflop to the river showdown with the cheapest
    // legal line: SB completes, BB checks the option, then both check every
    // postflop street until the river closes and the engine runs the showdown.
    private suspend fun GameSession.playToShowdown() {
        var nonce = 0
        // Preflop: button/SB acts first heads-up.
        applyIntent(actingUserId(), PlayerIntent.Call(seatIndex = state.value!!.actingSeatIndex!!), "p${nonce++}")
        applyIntent(actingUserId(), PlayerIntent.Check(seatIndex = state.value!!.actingSeatIndex!!), "p${nonce++}")
        // Flop, turn, river: check / check until the engine resolves the hand.
        while (state.value!!.street != BettingRound.Complete) {
            applyIntent(actingUserId(), PlayerIntent.Check(seatIndex = state.value!!.actingSeatIndex!!), "c${nonce++}")
        }
    }

    @Test
    fun showdownCompletion_firesOnHandFinishedOnce() = runTest {
        val finished = mutableListOf<HandOutcome>()
        val session = GameSession(
            random = Random(seed = 42),
            deckFactory = { showdownDeck() },
            onHandFinished = { outcome -> finished += outcome },
        )
        session.startHand(listOf(alice, bob), settings)
        runCurrent()

        session.playToShowdown()
        runCurrent()

        assertEquals(BettingRound.Complete, session.state.value!!.street, "the hand reached showdown")
        assertEquals(1, finished.size, "a showdown hand fires hand-finished exactly once, like a fold")
        assertEquals(setOf("alice", "bob"), finished.single().perHuman.keys)
        assertEquals(1, finished.single().handNumber)
    }

    @Test
    fun showdownCompletion_marksWinnerByShowdownNotFold() = runTest {
        val finished = mutableListOf<HandOutcome>()
        val session = GameSession(
            random = Random(seed = 42),
            deckFactory = { showdownDeck() },
            onHandFinished = { outcome -> finished += outcome },
        )
        session.startHand(listOf(alice, bob), settings)
        runCurrent()

        session.playToShowdown()
        runCurrent()

        val outcome = finished.single()
        assertTrue(outcome.perHuman.getValue("alice").won, "Aces win at showdown")
        assertTrue(!outcome.perHuman.getValue("bob").won, "Kings lose at showdown")
        assertTrue(
            !outcome.perHuman.getValue("alice").wonByFold,
            "a showdown win is not a fold win",
        )
    }
}
