package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.gameplay.SeatStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Unit coverage for [GameSession.rebuy] — the pure-engine half of the rebuy
 * flow (the wallet debit lives in the route, tested in
 * RoomSocketGameplayRoutesTest). Mirrors the style of the forfeitSeat tests in
 * GameSessionTest: hydrate a hand-Complete state with a busted seat, then assert
 * the refill / rejection contract.
 */
class GameSessionRebuyTest {

    private val settings = RoomSettings(
        smallBlind = 5,
        bigBlind = 10,
        startingStack = 1_000,
        maxSeats = 6,
        turnTimerSeconds = 30,
    )

    private fun newSession() = GameSession()

    /** A hand-Complete state where [bustedUser] has 0 chips and the other seat has chips. */
    private fun completedStateWithBustedSeat(
        bustedUser: String = "alice",
        street: BettingRound = BettingRound.Complete,
    ) = GameState(
        settings = settings,
        handNumber = 3,
        buttonSeatIndex = 0,
        seats = listOf(
            Seat(
                index = 0,
                playerId = bustedUser,
                displayName = "Alice",
                stack = 0,
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
        street = street,
        currentBetThisStreet = 0,
        lastFullRaiseSize = 0,
        actingSeatIndex = null,
        deckRemaining = emptyList(),
    )

    @Test
    fun rebuy_bustedSeat_refillsToStartingStack() = runTest {
        val session = newSession()
        // startHand seeds `settings` (so rebuy refills to the right buy-in), then
        // we hydrate the busted Complete state over it.
        session.startHand(
            listOf(
                SeatOccupant(seatIndex = 0, userId = "alice", displayName = "Alice", isBot = false),
                SeatOccupant(seatIndex = 1, userId = "bob", displayName = "Bob", isBot = false),
            ),
            settings,
        )
        session.hydrate(completedStateWithBustedSeat())

        val result = session.rebuy("alice", clientNonce = "n1")

        assertIs<IntentResult.Accepted>(result)
        val alice = session.state.value!!.seats.first { it.playerId == "alice" }
        assertEquals(settings.startingStack, alice.stack, "busted seat refilled to the buy-in")
    }

    @Test
    fun rebuy_notBusted_isRejected_andLeavesStackUntouched() = runTest {
        val session = newSession()
        session.startHand(
            listOf(
                SeatOccupant(seatIndex = 0, userId = "alice", displayName = "Alice", isBot = false),
                SeatOccupant(seatIndex = 1, userId = "bob", displayName = "Bob", isBot = false),
            ),
            settings,
        )
        session.hydrate(completedStateWithBustedSeat())

        // Bob still has chips — not a valid rebuy.
        val result = session.rebuy("bob", clientNonce = "n1")

        assertIs<IntentResult.Rejected>(result)
        assertEquals(2_000, session.state.value!!.seats.first { it.playerId == "bob" }.stack)
    }

    @Test
    fun rebuy_handInProgress_isRejected() = runTest {
        val session = newSession()
        session.startHand(
            listOf(
                SeatOccupant(seatIndex = 0, userId = "alice", displayName = "Alice", isBot = false),
                SeatOccupant(seatIndex = 1, userId = "bob", displayName = "Bob", isBot = false),
            ),
            settings,
        )
        session.hydrate(completedStateWithBustedSeat(street = BettingRound.Flop))

        val result = session.rebuy("alice", clientNonce = "n1")

        assertIs<IntentResult.Rejected>(result)
        assertEquals(0, session.state.value!!.seats.first { it.playerId == "alice" }.stack)
    }

    @Test
    fun rebuy_unknownUser_isRejected() = runTest {
        val session = newSession()
        session.startHand(
            listOf(
                SeatOccupant(seatIndex = 0, userId = "alice", displayName = "Alice", isBot = false),
                SeatOccupant(seatIndex = 1, userId = "bob", displayName = "Bob", isBot = false),
            ),
            settings,
        )
        session.hydrate(completedStateWithBustedSeat())

        val result = session.rebuy("nobody", clientNonce = "n1")

        assertIs<IntentResult.Rejected>(result)
    }

    @Test
    fun rebuy_twoRacingCallers_onlyOnePaysTheBuyIn() = runTest {
        // MP-38. The route used to check the busted seat off an unlocked read and
        // debit before calling in, so two rebuys racing the same bust could both
        // pay and the loser had to be compensated with a keyed refund. The debit
        // now runs inside this lock, after the seat check, so the loser never pays.
        val session = seatedSession()
        session.hydrate(completedStateWithBustedSeat())
        var buyInsTaken = 0

        val first = async { session.rebuy("alice", clientNonce = "a") { authorized { buyInsTaken++ } } }
        val second = async { session.rebuy("alice", clientNonce = "b") { authorized { buyInsTaken++ } } }
        val results = listOf(first.await(), second.await())

        assertEquals(1, buyInsTaken, "the seat is only busted once, so only one caller may be charged")
        assertEquals(1, results.count { it is IntentResult.Accepted })
        assertEquals(1, results.count { it is IntentResult.Rejected })
    }

    @Test
    fun rebuy_refusedBuyIn_leavesTheSeatBusted_andFreesTheNonceForARetry() = runTest {
        val session = seatedSession()
        session.hydrate(completedStateWithBustedSeat())

        val refused = session.rebuy("alice", clientNonce = "same") {
            IntentResult.Rejected("insufficient chips")
        }
        val retried = session.rebuy("alice", clientNonce = "same")

        assertIs<IntentResult.Rejected>(refused)
        assertIs<IntentResult.Accepted>(retried)
        assertEquals(
            settings.startingStack,
            session.state.value!!.seats.first { it.playerId == "alice" }.stack,
            "a refused buy-in must not burn the nonce — topping up and retrying has to work",
        )
    }

    @Test
    fun rebuy_buyInIsNotChargedWhenTheSeatIsNotBusted() = runTest {
        val session = seatedSession()
        session.hydrate(completedStateWithBustedSeat())
        var buyInsTaken = 0

        session.rebuy("bob", clientNonce = "n1") { authorized { buyInsTaken++ } }

        assertEquals(0, buyInsTaken, "an invalid rebuy must be refused before any money moves")
    }

    private inline fun authorized(charge: () -> Unit): IntentResult {
        charge()
        return IntentResult.Accepted
    }

    private suspend fun seatedSession(): GameSession = newSession().also {
        it.startHand(
            listOf(
                SeatOccupant(seatIndex = 0, userId = "alice", displayName = "Alice", isBot = false),
                SeatOccupant(seatIndex = 1, userId = "bob", displayName = "Bob", isBot = false),
            ),
            settings,
        )
    }

    @Test
    fun rebuy_sameNonce_isIdempotent() = runTest {
        val session = newSession()
        session.startHand(
            listOf(
                SeatOccupant(seatIndex = 0, userId = "alice", displayName = "Alice", isBot = false),
                SeatOccupant(seatIndex = 1, userId = "bob", displayName = "Bob", isBot = false),
            ),
            settings,
        )
        session.hydrate(completedStateWithBustedSeat())

        val first = session.rebuy("alice", clientNonce = "dup")
        // Re-bust the seat, then replay the same nonce — the dedupe swallows it.
        session.hydrate(completedStateWithBustedSeat())
        val second = session.rebuy("alice", clientNonce = "dup")

        assertIs<IntentResult.Accepted>(first)
        assertIs<IntentResult.Accepted>(second)
        assertEquals(
            0,
            session.state.value!!.seats.first { it.playerId == "alice" }.stack,
            "the replayed nonce was a no-op — the re-busted stack stayed 0",
        )
    }
}
