package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.playPassivelyToCompletion
import com.cards.integration.helpers.seatPrivate
import com.cards.integration.helpers.seatTwoAndConnect
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Wire-contract + snapshot-replay invariants.** The ack echoes the exact
 * nonce the client sent (so the client can correlate it), hole cards are scrubbed
 * per-recipient across a three-way table (not just heads-up), and a gameplay
 * collector mounting after a hand has already finished still replays the
 * completed table rather than sitting blank.
 */
class WireAndSnapshotTest : IntegrationTest() {

    @Test
    fun intentAck_echoesTheExactClientNonce() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()
        val dealt = table.hostGame.nextSnapshot { it.actingSeatIndex != null }
        val seat = dealt.actingSeatIndex!!

        val nonce = "test-nonce-7f3a-correlate-me"
        val ack = table.gameForSeat(dealt, seat).submitWithNonce(PlayerIntent.Call(seat), nonce)
        assertEquals(nonce, ack.clientNonce, "the server echoes the submit's nonce so the client can match it")
    }

    @Test
    fun holeCards_scrubbedPerRecipient_threeWay() = integration {
        val room = seatPrivate(humanCount = 3)
        room.hostGame.startHand()

        // Each of the three players reads their own view of the dealt hand.
        val views = room.games.map { it.nextSnapshot { s -> s.actingSeatIndex != null && s.seats.size == 3 } }

        room.clients.forEachIndexed { i, client ->
            val view = views[i]
            assertTrue(
                view.seats.first { it.playerId == client.userId }.holeCards.isNotEmpty(),
                "player $i sees their own hole cards",
            )
            view.seats.filter { it.playerId != client.userId }.forEach { other ->
                assertTrue(
                    other.holeCards.isEmpty(),
                    "player $i must NOT see seat ${other.index}'s hole cards",
                )
            }
        }
    }

    @Test
    fun collectorMountingAfterHandComplete_replaysTheFinishedTable() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()
        val completed = table.playPassivelyToCompletion()

        // A fresh gameplay collector on the same shared socket — "mounting the play
        // screen after the hand already ended" — must replay the retained final
        // snapshot, not wait forever for a frame that won't come.
        val late = gameplay(table.host.connect(table.code))
        val lateView = late.nextSnapshot { it.seats.isNotEmpty() }

        assertEquals(BettingRound.Complete, lateView.street, "the late collector sees the completed hand")
        assertEquals(
            completed.seats.sumOf { it.stack },
            lateView.seats.sumOf { it.stack },
            "the replayed table carries the final stacks",
        )
    }
}
