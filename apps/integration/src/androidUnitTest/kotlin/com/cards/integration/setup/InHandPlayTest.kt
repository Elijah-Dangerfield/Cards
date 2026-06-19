package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.seatFor
import com.cards.integration.helpers.seatTwoAndConnect
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * In-hand play through the REAL wire: two real clients, the real server gameplay
 * engine, real sockets. The heart of the mission — once players are seated, a hand
 * plays, the contract holds, and one client can't act for another.
 */
class InHandPlayTest : IntegrationTest() {

    @Test
    fun twoClients_playHeadsUpHand_toCompletion_viaFold() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()

        // Both clients see the hand dealt with someone to act.
        val dealt = table.hostGame.nextSnapshot { it.actingSeatIndex != null }
        table.joinerGame.nextSnapshot { it.actingSeatIndex != null }

        // The player to act folds; heads-up that ends the hand.
        val actingSeat = dealt.actingSeatIndex!!
        val ack = table.gameForSeat(dealt, actingSeat).submit(PlayerIntent.Fold(seatIndex = actingSeat))
        assertTrue(ack.accepted, "fold should be accepted, error=${ack.error}")

        table.hostGame.nextSnapshot { it.street == BettingRound.Complete }
        table.joinerGame.nextSnapshot { it.street == BettingRound.Complete }
    }

    @Test
    fun holeCards_areScrubbedPerRecipient() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()

        val hostView = table.hostGame.nextSnapshot { it.actingSeatIndex != null }
        val joinerView = table.joinerGame.nextSnapshot { it.actingSeatIndex != null }

        // Each client sees its own hole cards but never the opponent's.
        assertTrue(hostView.seatFor(table.host).holeCards.isNotEmpty(), "host should see its own cards")
        assertTrue(hostView.seatFor(table.joiner).holeCards.isEmpty(), "host must NOT see joiner's cards")
        assertTrue(joinerView.seatFor(table.joiner).holeCards.isNotEmpty(), "joiner should see its own cards")
        assertTrue(joinerView.seatFor(table.host).holeCards.isEmpty(), "joiner must NOT see host's cards")
    }

    @Test
    fun outOfTurnIntent_isRejected() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()
        val dealt = table.hostGame.nextSnapshot { it.actingSeatIndex != null }

        // The player who is NOT to act tries to fold their own seat — out of turn.
        val idle = table.other(table.actingClient(dealt))
        val ack = table.gameOf(idle).submit(PlayerIntent.Fold(seatIndex = dealt.seatFor(idle).index))
        assertFalse(ack.accepted, "an out-of-turn intent must be rejected")
    }

    /**
     * Regression for the "stuck on dealing in" bug. The existing tests attach
     * both clients' gameplay collectors *before* `startHand`, which masked the
     * defect: in the real app the non-host's `RemotePokerSession` subscribes
     * only when it navigates to the play screen — *after* the deal already
     * arrived on its (lobby-open) socket. With the old `replay = 0` flow that
     * snapshot was dropped and the table never rendered.
     *
     * Here we let the deal happen, then attach a *fresh* gameplay collector on
     * the joiner's already-open socket. Pre-fix it received nothing (no new
     * frames until someone acts) and `nextSnapshot` would time out; the
     * retained latest snapshot must now reach it.
     */
    @Test
    fun gameplayCollectorAttachingAfterTheDeal_stillSeesTheTable() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()
        // Deal is delivered to (and now retained on) the joiner's shared socket.
        table.hostGame.nextSnapshot { it.actingSeatIndex != null }
        table.joinerGame.nextSnapshot { it.actingSeatIndex != null }

        // The joiner "mounts the play screen" late: a new collector on the same
        // open socket, well after the deal.
        val lateJoinerGame = gameplay(table.joiner.connect(table.code))
        val lateView = lateJoinerGame.nextSnapshot { it.seats.isNotEmpty() }
        assertTrue(
            lateView.seats.any { it.playerId == table.joiner.userId },
            "a gameplay collector attaching after the deal must still receive the table",
        )
    }

    /**
     * Opponent avatar must ride the seat over the wire so the play screen
     * renders the real avatar instead of initials. The server snapshots each
     * member's profile avatar at join and copies it onto the engine seat at
     * hand-start.
     */
    @Test
    fun seat_carriesOpponentAvatar_overTheWire() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()
        val hostView = table.hostGame.nextSnapshot { it.actingSeatIndex != null }

        val joinerSeat = hostView.seatFor(table.joiner)
        assertFalse(
            joinerSeat.avatarEmoji.isNullOrBlank(),
            "the host must receive the joiner's avatar emoji on the seat, not a blank fallback",
        )
    }
}
