package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.driveToCompletion
import com.cards.integration.helpers.seatPrivate
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.rooms.JoinRoomOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * **Mid-hand leave → forfeit, and the seat lifecycle around it.** When a player
 * leaves a room mid-hand the server folds their live seat (`forfeitSeat`) so the
 * table doesn't stall on a gone player, and drops them from the *next* hand
 * (`removePlayer`). These prove that distinction over the wire on a 3-player
 * table where the hand must continue between the two survivors — and that a
 * player who left can come back and be dealt in again.
 *
 * `driveToCompletion` reads forward and only acts the seats handed to it, so the
 * departed seat (folded, never on the clock again) is carried by the other two.
 */
class ForfeitAndRemovePlayerTest : IntegrationTest() {

    @Test
    fun playerLeavesMidHand_seatIsFolded_andHandContinuesToCompletion() = integration {
        val room = seatPrivate(humanCount = 3)
        val (host, a, b) = Triple(room.clients[0], room.clients[1], room.clients[2])
        room.hostGame.startHand()
        room.hostGame.nextSnapshot { it.actingSeatIndex != null && it.seats.size == 3 }

        // `a` leaves while the hand is live → the server forfeits their seat. The
        // two survivors carry the hand to the end (no stall on the gone seat).
        a.repository.leaveRoom(room.code)
        val completed = driveToCompletion(room.hostGame, room.actableOf(host, b))

        assertEquals(BettingRound.Complete, completed.street, "the hand finished between the two who stayed")
        assertEquals(
            HandParticipation.Folded,
            completed.seats.first { it.playerId == a.userId }.handParticipation,
            "the departed player's seat was folded out, not left on the clock",
        )
    }

    @Test
    fun departedPlayer_foldedThisHand_isAbsentFromNextHand() = integration {
        val room = seatPrivate(humanCount = 3)
        val (host, a, b) = Triple(room.clients[0], room.clients[1], room.clients[2])
        room.hostGame.startHand()
        room.hostGame.nextSnapshot { it.actingSeatIndex != null && it.seats.size == 3 }

        a.repository.leaveRoom(room.code)
        val completed = driveToCompletion(room.hostGame, room.actableOf(host, b))

        // `removePlayer` vs `forfeitSeat`: the folded seat is still PRESENT in the
        // hand it left (forfeit governs only the current hand)...
        assertTrue(
            completed.seats.any { it.playerId == a.userId },
            "the departed player's folded seat is still part of the hand they left",
        )

        // ...but the NEXT hand excludes them entirely (removePlayer drops them).
        room.hostGame.requestNextHand()
        val handTwo = room.hostGame.nextSnapshot { it.handNumber == 2 && it.actingSeatIndex != null }
        assertEquals(2, handTwo.seats.size, "next hand is just the two who stayed")
        assertFalse(
            handTwo.seats.any { it.playerId == a.userId },
            "a player who left mid-hand is not re-dealt the next hand",
        )
    }

    @Test
    fun playerWhoLeftMidHand_canRejoin_andIsDealtInAgain() = integration {
        val room = seatPrivate(humanCount = 3, maxSeats = 3)
        val (host, a, b) = Triple(room.clients[0], room.clients[1], room.clients[2])
        room.hostGame.startHand()
        room.hostGame.nextSnapshot { it.actingSeatIndex != null && it.seats.size == 3 }

        // `a` leaves mid-hand (forfeited + removed for the session); the two
        // survivors finish hand 1 and play hand 2 (a stays out — F2 covers that).
        a.repository.leaveRoom(room.code)
        driveToCompletion(room.hostGame, room.actableOf(host, b))
        room.hostGame.requestNextHand()
        driveToCompletion(room.hostGame, room.actableOf(host, b)) // hand 2, host+b only

        // `a` comes back — a fresh session on the same identity (a new device /
        // re-navigation). The room is still Playing, so the join is accepted and
        // the connect queues them to be dealt in next.
        val aBack = client(userId = a.userId)
        assertIs<JoinRoomOutcome.Success>(aBack.repository.joinRoom(room.code))
        val aBackGame = gameplay(aBack.connect(room.code))
        aBackGame.nextSnapshot { it.seats.isNotEmpty() } // socket up → queued

        // Deal the next hand — the returned player must be dealt back in.
        // (Regression: removedPlayerIds was sticky for the whole session, so a
        // rejoiner was silently filtered out of every future hand; queueJoiner
        // now clears the mark.)
        room.hostGame.requestNextHand()
        val nextHand = room.hostGame.nextSnapshot { it.handNumber >= 3 && it.actingSeatIndex != null }
        assertTrue(
            nextHand.seats.any { it.playerId == a.userId },
            "a player who left mid-hand and rejoined is dealt back in",
        )
    }
}
