package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.seatTwoAndConnect
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * **The server says no, correctly.** Illegal gameplay intents, a spectator
 * trying to act, and a non-host trying to deal are each rejected over the wire
 * with an ack the client can surface — not silently dropped, not crashing the
 * socket.
 *
 * (Server 5xx-resilience isn't here: the harness has no clean 5xx-injection seam
 * — transport-drop resilience is covered by the chaos/reconnect tests instead.)
 */
class ErrorSurfacesTest : IntegrationTest() {

    @Test
    fun checkingWhileFacingABet_isRejected() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()
        val dealt = table.hostGame.nextSnapshot { it.actingSeatIndex != null }

        val seat = dealt.actingSeatIndex!!
        val owes = dealt.currentBetThisStreet - dealt.seatAt(seat).contributedThisStreet
        assertTrue(owes > 0, "the first actor preflop owes the blind difference")

        val ack = table.gameForSeat(dealt, seat).submit(PlayerIntent.Check(seat))
        assertFalse(ack.accepted, "you can't check while something is owed")
        assertFalse(ack.error.isNullOrBlank(), "the rejection carries a reason for the client")
    }

    @Test
    fun nonHost_startHand_isRejected() = integration {
        val table = seatTwoAndConnect()
        // The joiner is not the host — only the host deals a private table.
        val ack = table.joinerGame.startHandAwaitingAck()
        assertFalse(ack.accepted, "a non-host cannot start the hand")
    }

    @Test
    fun spectator_cannotAct() = integration {
        // An Open table is watchable by a non-member; that spectator may never act.
        val host = client()
        val created = assertIs<CreateRoomOutcome.Success>(host.repository.createRoom(open = true))
        val code = created.room.code

        val spectator = client() // never joins — connects as a read-only watcher
        val specGame = gameplay(spectator.connect(code))
        specGame.awaitConnected()

        val ack = specGame.submit(PlayerIntent.Fold(seatIndex = 0))
        assertFalse(ack.accepted, "a spectator's gameplay intent is rejected")
    }
}
