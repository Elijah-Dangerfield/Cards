package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.seatTwoAndConnect
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.rooms.JoinRoomOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * **Mid-hand join, end-to-end over the real wire.** The relaxed Private-room
 * join shipped 2026-06-24 lets a friend with the code enter a room while a hand
 * is in flight; the joiner spectates the live hand and is dealt in at the next
 * hand boundary via `queueMidHandJoinerIfNeeded` → `requestNextHand`. Before
 * this, the join was rejected `NotJoinable(Playing)` and the only mid-hand entry
 * was public matchmaking.
 *
 * These pin the full path: HTTP join into a Playing room, socket connect queues
 * the joiner, the live hand stays scrubbed for the not-yet-seated joiner, and the
 * next hand seats them — plus the dequeue path when a queued joiner leaves first.
 * The `seatTwoAndConnect` room is created at full capacity, so a third (and
 * fourth) client can join it.
 */
class MidHandJoinPlayTest : IntegrationTest() {

    @Test
    fun joinIntoPlayingPrivateRoom_succeeds_andAddsMember() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()
        // Hand is live → room is Playing. The relaxed gate must still accept a join.
        table.hostGame.nextSnapshot { it.actingSeatIndex != null }

        val latecomer = client()
        val outcome = latecomer.repository.joinRoom(table.code)

        val success = assertIs<JoinRoomOutcome.Success>(outcome)
        assertFalse(success.alreadyJoined, "a brand-new member, not an idempotent rejoin")
        assertTrue(
            success.room.members.any { it.userId == latecomer.userId },
            "the latecomer is now a member of the live room",
        )
        assertEquals(3, success.room.members.size, "host + joiner + latecomer")
    }

    @Test
    fun midHandJoiner_isQueued_andDealtInAtNextHand() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()
        val dealt = table.hostGame.nextSnapshot { it.actingSeatIndex != null }
        assertEquals(2, dealt.seats.size, "hand 1 is heads-up: just host + joiner")

        // Latecomer joins mid-hand and opens a socket. The socket-open handler
        // queues them before its game publisher launches, so the first snapshot
        // they receive proves the queue already ran.
        val latecomer = client()
        assertIs<JoinRoomOutcome.Success>(latecomer.repository.joinRoom(table.code))
        val latecomerGame = gameplay(latecomer.connect(table.code))
        latecomerGame.nextSnapshot { it.seats.isNotEmpty() }

        // Hand 1 ends (the player to act folds — heads-up, that completes it).
        val actingSeat = dealt.actingSeatIndex!!
        table.gameForSeat(dealt, actingSeat).submit(PlayerIntent.Fold(actingSeat))
        table.hostGame.nextSnapshot { it.street == BettingRound.Complete }

        // Next hand: the queued joiner is folded into the deal alongside the two
        // returning players with chips.
        table.hostGame.requestNextHand()
        val handTwo = table.hostGame.nextSnapshot { it.handNumber == 2 && it.actingSeatIndex != null }
        assertEquals(3, handTwo.seats.size, "next hand seats all three")
        assertTrue(
            handTwo.seats.any { it.playerId == latecomer.userId },
            "the mid-hand joiner is dealt into the next hand",
        )
        // And the joiner's own socket sees itself seated with hole cards.
        val latecomerView = latecomerGame.nextSnapshot { it.handNumber == 2 && it.seats.isNotEmpty() }
        assertTrue(
            latecomerView.seats.first { it.playerId == latecomer.userId }.holeCards.isNotEmpty(),
            "the dealt-in joiner sees its own hole cards",
        )
    }

    @Test
    fun midHandJoiner_spectatesLiveHand_withoutASeat_andCardsScrubbed() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()
        table.hostGame.nextSnapshot { it.actingSeatIndex != null }

        val latecomer = client()
        assertIs<JoinRoomOutcome.Success>(latecomer.repository.joinRoom(table.code))
        val latecomerGame = gameplay(latecomer.connect(table.code))

        // The joiner watches hand 1 but holds no seat in it, and the two live
        // seats' hole cards are scrubbed from their spectator view.
        val view = latecomerGame.nextSnapshot { it.handNumber == 1 && it.seats.isNotEmpty() }
        assertEquals(2, view.seats.size, "the live hand still has only the two original players")
        assertFalse(
            view.seats.any { it.playerId == latecomer.userId },
            "the joiner is not seated in the in-flight hand",
        )
        assertTrue(
            view.seats.all { it.holeCards.isEmpty() },
            "a spectator sees no hole cards for the live seats",
        )
    }

    @Test
    fun twoMidHandJoiners_bothDealtInAtNextHand_atDistinctSeats() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()
        val dealt = table.hostGame.nextSnapshot { it.actingSeatIndex != null }

        val joinerB = client()
        val joinerC = client()
        assertIs<JoinRoomOutcome.Success>(joinerB.repository.joinRoom(table.code))
        assertIs<JoinRoomOutcome.Success>(joinerC.repository.joinRoom(table.code))
        val gameB = gameplay(joinerB.connect(table.code))
        val gameC = gameplay(joinerC.connect(table.code))
        gameB.nextSnapshot { it.seats.isNotEmpty() }
        gameC.nextSnapshot { it.seats.isNotEmpty() }

        val actingSeat = dealt.actingSeatIndex!!
        table.gameForSeat(dealt, actingSeat).submit(PlayerIntent.Fold(actingSeat))
        table.hostGame.nextSnapshot { it.street == BettingRound.Complete }

        table.hostGame.requestNextHand()
        val handTwo = table.hostGame.nextSnapshot { it.handNumber == 2 && it.actingSeatIndex != null }

        assertEquals(4, handTwo.seats.size, "both joiners dealt in alongside the two returning players")
        val seatsById = handTwo.seats.mapNotNull { it.playerId }
        assertTrue(joinerB.userId in seatsById, "joiner B seated")
        assertTrue(joinerC.userId in seatsById, "joiner C seated")
        assertEquals(
            handTwo.seats.map { it.index }.distinct().size,
            handTwo.seats.size,
            "every seat index is distinct — no two players share a seat",
        )
    }

    @Test
    fun midHandJoiner_wholeavesBeforeNextHand_isNotDealtIn() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()
        val dealt = table.hostGame.nextSnapshot { it.actingSeatIndex != null }

        // Joiner queues mid-hand, then leaves before the next deal.
        val latecomer = client()
        assertIs<JoinRoomOutcome.Success>(latecomer.repository.joinRoom(table.code))
        val latecomerGame = gameplay(latecomer.connect(table.code))
        latecomerGame.nextSnapshot { it.seats.isNotEmpty() } // ensure the queue ran
        table.hostGame.awaitRoom { it.members.size == 3 } // host saw the joiner arrive
        latecomer.repository.leaveRoom(table.code)
        // Sync on the host observing the membership drop back to two: the server
        // runs the dequeue right after sending that snapshot, so by the time this
        // round-trips to the client the joiner is out of the pending queue.
        table.hostGame.awaitRoom { it.members.size == 2 }

        // Finish hand 1 and deal the next.
        val actingSeat = dealt.actingSeatIndex!!
        table.gameForSeat(dealt, actingSeat).submit(PlayerIntent.Fold(actingSeat))
        table.hostGame.nextSnapshot { it.street == BettingRound.Complete }
        table.hostGame.requestNextHand()

        val handTwo = table.hostGame.nextSnapshot { it.handNumber == 2 && it.actingSeatIndex != null }
        assertEquals(2, handTwo.seats.size, "the departed joiner was dequeued — next hand is still heads-up")
        assertFalse(
            handTwo.seats.any { it.playerId == latecomer.userId },
            "a joiner who left before the next hand is never dealt in",
        )
    }
}
