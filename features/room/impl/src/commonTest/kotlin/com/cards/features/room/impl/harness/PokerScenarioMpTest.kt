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

    @Test
    fun opponentsLeft_firesOnceOnlyWhenTheLastHumanRemains() = runUnitTest {
        val s = mpScenario(localUserId = MP_LOCAL_USER).start()

        // Three humans seated.
        s.serverConnection(
            RoomConnection.Connected(roomWith(member(MP_LOCAL_USER), member("p2"), member("p3"))),
        )
        // One leaves — still two humans, so no signal yet.
        s.serverConnection(RoomConnection.Connected(roomWith(member(MP_LOCAL_USER), member("p3"))))
        assertFalse(
            s.events.events.contains(PlayPokerEvent.OpponentsLeft),
            "two humans remain — not yet the last one standing",
        )

        // The second opponent leaves — now the local player is alone.
        s.serverConnection(RoomConnection.Connected(roomWith(member(MP_LOCAL_USER))))

        assertEquals(
            1,
            s.events.events.count { it == PlayPokerEvent.OpponentsLeft },
            "OpponentsLeft fires exactly once on the drop to the last human",
        )
    }

    @Test
    fun realMpBust_opensHandResult_andStateIsRealMultiplayer() = runUnitTest {
        val s = mpScenario(localUserId = MP_LOCAL_USER).start()
        val board = cards("Ah Kd 7c 2s 9h")

        // Showdown snapshot: the local human is busted (stack 0), peer wins.
        s.serverSnapshot(
            mpTable(
                seats = listOf(
                    mpSeat(0, playerId = MP_LOCAL_USER, stack = 0, holeCards = cards("Qs Qd")),
                    mpSeat(1, playerId = "peer", stack = 2_000, holeCards = cards("As Ad")),
                ),
                actingSeatIndex = null,
                street = BettingRound.Complete,
                community = board,
            ),
        )
        s.serverEvent(
            GameEvent.HandEnded(
                sequence = 7,
                winners = listOf(HandWinner(seatIndex = 1, amount = 2_000, handRank = null, byFold = false)),
                board = board,
                revealedHoleCards = mapOf(0 to cards("Qs Qd"), 1 to cards("As Ad")),
            ),
        )

        assertTrue(
            s.vm.state.isRealMultiplayer,
            "two humans, no bots-only → the screen shows the terminal MP bust dialog",
        )
        assertTable(s.table) {
            handResultShowing()
            seatBusted(seat = 0)
        }
    }

    @Test
    fun matchOverPending_bustedRole_setsBustedCountdown() = runUnitTest {
        val s = mpScenario(localUserId = MP_LOCAL_USER).start()
        // Local human (seat 0) busted heads-up; the peer (seat 1) has the chips.
        s.serverSnapshot(
            mpTable(
                seats = listOf(
                    mpSeat(0, playerId = MP_LOCAL_USER, stack = 0),
                    mpSeat(1, playerId = "peer", stack = 2_000),
                ),
                actingSeatIndex = null,
                street = BettingRound.Complete,
            ),
        )

        s.serverMatchOverPending(deadlineEpochMs = 60_000L, bustedSeatIndex = 0)

        val countdown = s.vm.state.matchOverCountdown
        assertEquals(60_000L, countdown?.deadlineEpochMs)
        assertTrue(countdown?.localPlayerIsBusted == true, "local player is the busted seat")
    }

    @Test
    fun matchOverPending_winnerRole_setsWinnerCountdown() = runUnitTest {
        val s = mpScenario(localUserId = MP_LOCAL_USER).start()
        // Local human (seat 0) won; the peer (seat 1) busted.
        s.serverSnapshot(
            mpTable(
                seats = listOf(
                    mpSeat(0, playerId = MP_LOCAL_USER, stack = 2_000),
                    mpSeat(1, playerId = "peer", stack = 0),
                ),
                actingSeatIndex = null,
                street = BettingRound.Complete,
            ),
        )

        s.serverMatchOverPending(deadlineEpochMs = 60_000L, bustedSeatIndex = 1)

        val countdown = s.vm.state.matchOverCountdown
        assertEquals(60_000L, countdown?.deadlineEpochMs)
        assertFalse(countdown?.localPlayerIsBusted == true, "local player is the winner, not busted")
    }

    @Test
    fun matchOverCleared_afterRebuy_clearsCountdown() = runUnitTest {
        val s = mpScenario(localUserId = MP_LOCAL_USER).start()
        s.serverSnapshot(
            mpTable(
                seats = listOf(
                    mpSeat(0, playerId = MP_LOCAL_USER, stack = 0),
                    mpSeat(1, playerId = "peer", stack = 2_000),
                ),
                actingSeatIndex = null,
                street = BettingRound.Complete,
            ),
        )
        s.serverMatchOverPending(deadlineEpochMs = 60_000L, bustedSeatIndex = 0)
        assertTrue(s.vm.state.matchOverCountdown != null)

        // The busted player rebought inside the window — the server clears it.
        s.serverMatchOverCleared()

        assertEquals(null, s.vm.state.matchOverCountdown, "a rebuy clears the countdown")
    }

    @Test
    fun matchOverResolved_winner_surfacesWonResult_notSilentClose() = runUnitTest {
        val s = mpScenario(localUserId = MP_LOCAL_USER).start()
        s.serverSnapshot(
            mpTable(
                seats = listOf(
                    mpSeat(0, playerId = MP_LOCAL_USER, stack = 2_000),
                    mpSeat(1, playerId = "peer", stack = 0),
                ),
                actingSeatIndex = null,
                street = BettingRound.Complete,
            ),
        )

        s.serverMatchOverResolved(winnerUserId = MP_LOCAL_USER)

        assertTrue(
            s.vm.state.matchOverResult?.localPlayerWon == true,
            "the winner sees a won result overlay, not a silent pop; got ${s.vm.state.matchOverResult}",
        )
        // A match-over routes through the result overlay, NOT the generic
        // RoomClosed exit (which would pop silently).
        assertFalse(
            s.events.events.any { it is PlayPokerEvent.RoomClosed },
            "match-over must not fire the generic RoomClosed exit; got ${s.events.events}",
        )
    }

    @Test
    fun matchOverResolved_loser_surfacesLostResult() = runUnitTest {
        val s = mpScenario(localUserId = MP_LOCAL_USER).start()
        s.serverSnapshot(
            mpTable(
                seats = listOf(
                    mpSeat(0, playerId = MP_LOCAL_USER, stack = 0),
                    mpSeat(1, playerId = "peer", stack = 2_000),
                ),
                actingSeatIndex = null,
                street = BettingRound.Complete,
            ),
        )

        s.serverMatchOverResolved(winnerUserId = "peer")

        assertEquals(
            false,
            s.vm.state.matchOverResult?.localPlayerWon,
            "the busted player sees a lost result; got ${s.vm.state.matchOverResult}",
        )
    }

    @Test
    fun staleSnapshot_fromAnEarlierHand_isDropped() = runUnitTest {
        val s = mpScenario(localUserId = MP_LOCAL_USER).start()
        val seats = listOf(mpSeat(0, playerId = MP_LOCAL_USER), mpSeat(1, playerId = "peer"))

        // Live on hand 2, the local player to act.
        s.serverSnapshot(mpTable(seats = seats, actingSeatIndex = 0, handNumber = 2, lastSequence = 5))
        assertTable(s.table) { handNumber(2); isHumanTurn(true) }

        // A late, out-of-order hand-1 snapshot must not clobber the live table.
        s.serverSnapshot(mpTable(seats = seats, actingSeatIndex = 1, handNumber = 1, lastSequence = 99))

        assertTable(s.table) {
            handNumber(2)
            isHumanTurn(true)
        }
    }

    @Test
    fun addFriend_sendsRequest_andFlipsSeatToSent() = runUnitTest {
        val s = mpScenario().start()
        s.serverSnapshot(
            mpTable(
                seats = listOf(
                    mpSeat(0, playerId = MP_LOCAL_USER, displayName = "Alice"),
                    mpSeat(1, playerId = "peer", displayName = "Bob"),
                ),
                actingSeatIndex = 0,
            ),
        )

        s.vm.takeAction(PlayPokerAction.AddFriend("peer"))

        assertEquals(listOf("peer"), s.friendRepository.sentTo)
        assertTrue("peer" in s.vm.state.friendRequestSentIds)
    }

    @Test
    fun addFriend_rejectedByServer_unflipsSentState() = runUnitTest {
        val s = mpScenario().start()
        s.friendRepository.nextResult =
            com.dangerfield.cards.libraries.social.SendFriendRequestResult.NotPlayedWith
        s.serverSnapshot(
            mpTable(
                seats = listOf(
                    mpSeat(0, playerId = MP_LOCAL_USER, displayName = "Alice"),
                    mpSeat(1, playerId = "peer", displayName = "Bob"),
                ),
                actingSeatIndex = 0,
            ),
        )

        s.vm.takeAction(PlayPokerAction.AddFriend("peer"))

        assertEquals(listOf("peer"), s.friendRepository.sentTo)
        assertFalse("peer" in s.vm.state.friendRequestSentIds)
    }

    @Test
    fun addFriend_isIdempotent_onDoubleTap() = runUnitTest {
        val s = mpScenario().start()
        s.serverSnapshot(
            mpTable(
                seats = listOf(
                    mpSeat(0, playerId = MP_LOCAL_USER, displayName = "Alice"),
                    mpSeat(1, playerId = "peer", displayName = "Bob"),
                ),
                actingSeatIndex = 0,
            ),
        )

        s.vm.takeAction(PlayPokerAction.AddFriend("peer"))
        s.vm.takeAction(PlayPokerAction.AddFriend("peer"))

        assertEquals(listOf("peer"), s.friendRepository.sentTo)
    }

    @Test
    fun submitThatGetsNoAck_surfacesTimedOutHint_notSilentPause() = runUnitTest {
        val s = mpScenario().start()
        s.serverSnapshot(
            mpTable(
                seats = listOf(mpSeat(0, playerId = MP_LOCAL_USER), mpSeat(1, playerId = "peer")),
                actingSeatIndex = 0,
                currentBetThisStreet = 10,
            ),
        )

        // The server never acks — the submit times out after INTENT_TIMEOUT_MS.
        s.iSubmitAndLetTimeOut(PlayerIntent.Fold(seatIndex = 0))

        assertTrue(
            s.events.events.contains(PlayPokerEvent.IntentFeedback(IntentFeedbackKind.TimedOut)),
            "a submit that never acks must surface a timed-out hint, not a dead pause; got ${s.events.events}",
        )
    }

    @Test
    fun submitRejectedByServer_surfacesRejectedHint() = runUnitTest {
        val s = mpScenario().start()
        s.serverSnapshot(
            mpTable(
                seats = listOf(mpSeat(0, playerId = MP_LOCAL_USER), mpSeat(1, playerId = "peer")),
                actingSeatIndex = 0,
                currentBetThisStreet = 10,
            ),
        )

        s.iSubmitAndAck(PlayerIntent.Fold(seatIndex = 0), accepted = false, error = "not your turn")

        assertTrue(
            s.events.events.contains(PlayPokerEvent.IntentFeedback(IntentFeedbackKind.Rejected)),
            "a rejected submit must surface a not-allowed hint; got ${s.events.events}",
        )
    }

    @Test
    fun nextHandRefused_emitsNextHandUnavailable() = runUnitTest {
        val s = mpScenario().start()
        s.serverSnapshot(
            mpTable(
                seats = listOf(
                    mpSeat(0, playerId = MP_LOCAL_USER, stack = 2_000),
                    mpSeat(1, playerId = "peer", stack = 0),
                ),
                actingSeatIndex = null,
                street = BettingRound.Complete,
            ),
        )

        // Heads-up, the opponent busted to 0 with no rebuy — the server refuses
        // the winner's "next hand" tap with the canonical can't-deal reason.
        s.serverRefusesNextHand(error = "not enough players with chips for next hand")

        assertTrue(
            s.events.events.contains(PlayPokerEvent.NextHandUnavailable),
            "a genuine can't-deal refusal must surface NextHandUnavailable so the tap isn't a silent no-op; got ${s.events.events}",
        )
    }

    @Test
    fun nextHandRefused_transientRace_emitsResyncing_notRebuyToast() = runUnitTest {
        // MP-22: a "current hand not complete" refusal (a stale-snapshot race
        // after the user backgrounded) must NOT show the terminal rebuy copy —
        // it resyncs off the live snapshot stream instead.
        val s = mpScenario().start()
        s.serverSnapshot(
            mpTable(
                seats = listOf(
                    mpSeat(0, playerId = MP_LOCAL_USER, stack = 2_000),
                    mpSeat(1, playerId = "peer", stack = 1_000),
                ),
                actingSeatIndex = null,
                street = BettingRound.Complete,
            ),
        )

        s.serverRefusesNextHand(error = "current hand not complete")

        assertTrue(
            s.events.events.contains(PlayPokerEvent.NextHandResyncing),
            "a transient refusal must surface NextHandResyncing; got ${s.events.events}",
        )
        assertTrue(
            !s.events.events.contains(PlayPokerEvent.NextHandUnavailable),
            "a transient refusal must NOT surface the terminal rebuy toast; got ${s.events.events}",
        )
    }

    @Test
    fun reconnectFailed_isTerminal_firesRoomClosedExit() = runUnitTest {
        val s = mpScenario().start()
        s.serverSnapshot(
            mpTable(
                seats = listOf(mpSeat(0, playerId = MP_LOCAL_USER), mpSeat(1, playerId = "peer")),
                actingSeatIndex = 1,
            ),
        )

        // The socket gave up after repeated half-open reconnects — terminal, the
        // screen must pop rather than spin on the reconnecting banner forever.
        s.serverConnection(RoomConnection.Closed(ClosedReason.ReconnectFailed))

        assertTrue(
            s.events.events.contains(PlayPokerEvent.RoomClosed(ClosedReason.ReconnectFailed)),
            "ReconnectFailed is terminal and must fan out a RoomClosed exit; got ${s.events.events}",
        )
    }

    @Test
    fun midGameJoiner_waitsToBeDealtIn_thenSeatsOnNextHandSnapshot() = runUnitTest {
        val s = mpScenario(localUserId = "joiner").start()

        // Joined mid-hand: the snapshot has no seat for the local user, so they
        // spectate with a "dealt in next hand" notice rather than waiting forever.
        s.serverSnapshot(
            mpTable(
                seats = listOf(mpSeat(0, playerId = "p1"), mpSeat(1, playerId = "p2")),
                actingSeatIndex = 0,
            ),
        )
        assertTrue(
            s.table.waitingToBeDealtIn,
            "a seatless mid-game joiner must show the waiting-to-be-dealt-in notice",
        )

        // The next-hand snapshot seats them — the notice clears and it's a real seat.
        s.serverSnapshot(
            mpTable(
                seats = listOf(
                    mpSeat(0, playerId = "p1"),
                    mpSeat(1, playerId = "p2"),
                    mpSeat(2, playerId = "joiner"),
                ),
                actingSeatIndex = 2,
                handNumber = 2,
            ),
        )
        assertFalse(
            s.table.waitingToBeDealtIn,
            "once dealt in, the joiner is seated and the notice clears",
        )
        assertEquals(2, s.table.seats.single { it.isHuman }.index)
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
