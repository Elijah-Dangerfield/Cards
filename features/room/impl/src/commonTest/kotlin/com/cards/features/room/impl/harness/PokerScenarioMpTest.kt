package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.game.ConnectionState
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.HandWinner
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.rooms.ClientFrame
import com.dangerfield.cards.libraries.rooms.ClosedReason
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomConnection
import com.dangerfield.cards.libraries.rooms.RoomMember
import com.dangerfield.cards.libraries.rooms.RoomStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Multiplayer behaviour scenarios. The server is authoritative, so an opponent
 * action is injected as the frames the server would send — a [GameState]
 * snapshot plus (for a pill) a [GameEvent.ActionTaken] — and assertions use the
 * same [assertTable] vocabulary as the solo suite.
 *
 * Heads-up table: the local user is seat 0 ([MP_LOCAL_USER]); the peer is seat 1.
 */
class PokerScenarioMpTest : PokerScenarioTest() {

    @Test
    fun firstSnapshot_seatsLocalHuman_andItIsTheirTurn() = runUnitTest {
        val s = mpScenario().start()

        s.serverSnapshot(
            mpTable(
                seats = listOf(
                    mpSeat(0, playerId = MP_LOCAL_USER, displayName = "Alice"),
                    mpSeat(1, playerId = "peer", displayName = "Bob"),
                ),
                actingSeatIndex = 0,
                currentBetThisStreet = 10,
            ),
        )

        assertTable(s.table) { isHumanTurn(true) }
        assertEquals(0, s.table.seats.single { it.isHuman }.index)
    }

    @Test
    fun opponentRaisesTo200_humanFacesCall_andSeesRaisePill() = runUnitTest {
        val s = mpScenario().start()

        s.opponentActs(
            action = GameEvent.ActionTaken(
                sequence = 1,
                seatIndex = 1,
                action = PlayerAction.Raise(totalStreetContribution = 200, raiseAmount = 190),
                resultingStreetContribution = 200,
            ),
            resultingState = mpTable(
                seats = listOf(
                    mpSeat(0, playerId = MP_LOCAL_USER, displayName = "Alice", stack = 990, contributedThisStreet = 10),
                    mpSeat(1, playerId = "peer", displayName = "Bob", stack = 800, contributedThisStreet = 200),
                ),
                actingSeatIndex = 0,
                currentBetThisStreet = 200,
                lastFullRaiseSize = 190,
            ),
        )

        assertTable(s.table) {
            isHumanTurn(true)
            humanCannotCheck()
            humanCanCall(190) // 200 − 10 already in
            seatPill(1, "Raised to 200")
        }
    }

    @Test
    fun humanFold_sendsSubmitIntentFrameOverWire() = runUnitTest {
        val s = mpScenario().start()
        s.serverSnapshot(
            mpTable(
                seats = listOf(
                    mpSeat(0, playerId = MP_LOCAL_USER),
                    mpSeat(1, playerId = "peer"),
                ),
                actingSeatIndex = 0,
                currentBetThisStreet = 10,
            ),
        )

        s.iSubmitAndAck(PlayerIntent.Fold(seatIndex = 0))

        val frame = assertIs<ClientFrame.SubmitIntent>(s.lastSent())
        assertEquals(PlayerIntent.Fold(seatIndex = 0), frame.intent)
    }

    @Test
    fun serverPushesShowdown_handResultDialogOpens_withWinner() = runUnitTest {
        val s = mpScenario().start()
        val board = cards("Ah Kd 7c 2s 9h")

        s.serverSnapshot(
            mpTable(
                seats = listOf(
                    mpSeat(0, playerId = MP_LOCAL_USER, holeCards = cards("As Ad")),
                    mpSeat(1, playerId = "peer", holeCards = cards("Kh Kc")),
                ),
                actingSeatIndex = null,
                street = BettingRound.Complete,
                community = board,
            ),
        )
        s.serverEvent(
            GameEvent.HandEnded(
                sequence = 5,
                winners = listOf(HandWinner(seatIndex = 0, amount = 400, handRank = null, byFold = false)),
                board = board,
                revealedHoleCards = mapOf(0 to cards("As Ad"), 1 to cards("Kh Kc")),
            ),
        )

        assertTable(s.table) {
            handResultShowing()
            handResultWinner(seat = 0)
        }
    }

    @Test
    fun roomClosedMidSession_recordsRoomClosedEvent() = runUnitTest {
        val s = mpScenario().start()
        s.serverSnapshot(
            mpTable(
                seats = listOf(mpSeat(0, playerId = MP_LOCAL_USER), mpSeat(1, playerId = "peer")),
                actingSeatIndex = 1,
            ),
        )

        s.serverConnection(RoomConnection.Closed(ClosedReason.RoomDeleted))

        assertTrue(
            s.events.events.contains(PlayPokerEvent.RoomClosed(ClosedReason.RoomDeleted)),
            "a terminal room close must fan out a one-shot exit event; got ${s.events.events}",
        )
    }

    @Test
    fun reconnectBlip_tablePersists_thenResyncsToFreshSnapshot() = runUnitTest {
        val s = mpScenario().start()
        val peerActing = mpTable(
            seats = listOf(mpSeat(0, playerId = MP_LOCAL_USER), mpSeat(1, playerId = "peer")),
            actingSeatIndex = 1,
        )

        s.serverConnection(RoomConnection.Connected(sampleRoom()))
        s.serverSnapshot(peerActing)
        assertEquals(ConnectionState.Connected, s.connection)
        assertFalse(s.table.isHumanTurn, "peer is acting")

        // The blip must not blank the table.
        s.serverConnection(RoomConnection.Reconnecting(attempt = 1, cause = null))
        assertEquals(ConnectionState.Reconnecting, s.connection)
        assertTable(s.table) { isHumanTurn(false) }

        // On resume a fresh snapshot resyncs the table.
        s.serverConnection(RoomConnection.Connected(sampleRoom()))
        s.serverSnapshot(
            mpTable(
                seats = listOf(mpSeat(0, playerId = MP_LOCAL_USER), mpSeat(1, playerId = "peer")),
                actingSeatIndex = 0,
                lastSequence = 1,
            ),
        )
        assertEquals(ConnectionState.Connected, s.connection)
        assertTable(s.table) { isHumanTurn(true) }
    }

    @Test
    fun allOtherHumansLeave_emitsOpponentsLeft() = runUnitTest {
        val s = mpScenario(localUserId = MP_LOCAL_USER).start()

        // Baseline: two humans seated.
        s.serverConnection(
            RoomConnection.Connected(roomWith(member(MP_LOCAL_USER), member("peer"))),
        )
        // The peer leaves — the local user is the last human standing.
        s.serverConnection(
            RoomConnection.Connected(roomWith(member(MP_LOCAL_USER))),
        )

        assertTrue(
            s.events.events.contains(PlayPokerEvent.OpponentsLeft),
            "dropping to the last human fans out OpponentsLeft; got ${s.events.events}",
        )
    }

    @Test
    fun practiceWithBots_neverEmitsOpponentsLeft() = runUnitTest {
        val s = mpScenario(localUserId = MP_LOCAL_USER).start()

        // A game that begins with a single human + bots must never read as
        // "opponents left" — there were never two humans to drop from.
        val table = roomWith(member(MP_LOCAL_USER), member("bot-1", isBot = true))
        s.serverConnection(RoomConnection.Connected(table))
        s.serverConnection(RoomConnection.Connected(table))

        assertFalse(s.events.events.contains(PlayPokerEvent.OpponentsLeft))
    }

    private fun member(userId: String, isBot: Boolean = false): RoomMember = RoomMember(
        userId = userId,
        displayName = userId,
        seatIndex = 0,
        joinedAtEpochMs = 0L,
        isConnected = true,
        isBot = isBot,
    )

    private fun roomWith(vararg members: RoomMember): Room = Room(
        code = "ABCDEF",
        hostUserId = MP_LOCAL_USER,
        createdAtEpochMs = 0L,
        maxSeats = 6,
        status = RoomStatus.Playing,
        members = members.toList(),
    )

    private fun sampleRoom(): Room = Room(
        code = "ABCDEF",
        hostUserId = "host",
        createdAtEpochMs = 0L,
        maxSeats = 6,
        status = RoomStatus.Playing,
        members = emptyList(),
    )
}
