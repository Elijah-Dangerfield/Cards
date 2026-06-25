package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.rooms.ClientFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Full turn-cycle multiplayer scenarios driven by [FakeRoomServer] — the server
 * runs a real [com.dangerfield.cards.libraries.gameplay.GameEngine] and
 * auto-plays the opponent seat, so these cover the client's deal → act → settle
 * path end-to-end without hand-feeding every frame (as [PokerScenarioMpTest]
 * does) and without booting Ktor.
 *
 * Heads-up table: the local user is seat 0; the peer is seat 1, auto-played by
 * the server. The client under test only ever submits its own (seat 0) actions.
 */
class FakeRoomServerTest : PokerScenarioTest() {

    private val occupants = listOf(
        ServerSeat(seatIndex = 0, userId = MP_LOCAL_USER, displayName = "Alice"),
        ServerSeat(seatIndex = 1, userId = "peer", displayName = "Bob"),
    )

    @Test
    fun startHand_dealsRealHand_withAnActingSeat() = runUnitTest {
        val s = mpScenario().withServer(occupants).start()

        s.serverStartHand()

        assertTable(s.table) {
            handNumber(1)
            street(BettingRound.Preflop)
        }
        assertEquals(2, s.serverState.seats.size)
        assertNotNull(s.serverState.actingSeatIndex, "the dealt hand must have an acting seat")
    }

    @Test
    fun humanFolds_serverRunsEngine_handEndsWithOpponentAsSoleWinner() = runUnitTest {
        val s = mpScenario().withServer(occupants).start()
        s.serverStartHand()

        // Heads-up: the server auto-plays the peer up to our turn; folding ends
        // the hand with the peer (seat 1) as the sole winner by fold.
        submitLocalUntilSettled(s, fold = true)

        assertTable(s.table) {
            handResultShowing()
            handResultWinner(seat = 1)
        }
        assertEquals(1, assertNotNull(s.table.handResult).winners.size)
    }

    @Test
    fun fullHand_callsDownToShowdown_andTheBestHandWins() = runUnitTest {
        // Seat 0 holds aces over the peer's kings on a blank board; the local user
        // checks / calls every street and the passive peer (CheckOrCall) does the
        // same, so the hand reaches showdown and the engine awards seat 0.
        val s = mpScenario()
            .withServer(
                occupants = occupants,
                deckFactory = { _ ->
                    stackedDeck(
                        holeBySeat = listOf(cards("As Ad"), cards("Kh Kc")),
                        board = cards("Qh Jd 7c 2s 9h"),
                    )
                },
            )
            .start()
        s.serverStartHand()

        submitLocalUntilSettled(s, fold = false)

        assertTable(s.table) {
            handResultShowing()
            handResultWinner(seat = 0)
        }
    }

    @Test
    fun submitIntent_reachesServer_asSubmitIntentFrame() = runUnitTest {
        val s = mpScenario().withServer(occupants).start()
        s.serverStartHand()

        submitLocalUntilSettled(s, fold = true)

        assertTrue(
            s.serverReceived().any { it is ClientFrame.SubmitIntent },
            "a submit must reach the server as a SubmitIntent frame",
        )
    }

    /**
     * Drive only the local (seat 0) actions until the hand settles. When [fold]
     * the local user folds on its first turn (ending a heads-up hand); otherwise
     * it checks or calls every street down to showdown. The server auto-plays the
     * opponent between local turns.
     */
    private suspend fun submitLocalUntilSettled(s: RunningMpScenario, fold: Boolean) {
        var guard = 0
        while (s.table.handResult == null && guard++ < 20) {
            val state = s.serverState
            if (state.street == BettingRound.Complete) break
            if (state.actingSeatIndex != 0) break
            val seat = state.seats.first { it.index == 0 }
            val intent = when {
                fold -> PlayerIntent.Fold(seatIndex = 0)
                state.currentBetThisStreet > seat.contributedThisStreet -> PlayerIntent.Call(seatIndex = 0)
                else -> PlayerIntent.Check(seatIndex = 0)
            }
            s.iSubmit(intent)
        }
    }
}
