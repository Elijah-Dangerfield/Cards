package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.seatTwoAndConnect
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Network chaos during a live hand — the cases users actually hit on real
 * networks. Uses the [com.cards.integration.helpers.FaultInjectingTransport] to
 * drop a real socket mid-hand and asserts the client recovers through the real
 * reconnect machinery.
 */
class ChaosPlayTest : IntegrationTest() {

    @Test
    fun clientDropsMidHand_reconnects_andStillSeesHandComplete() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()

        val dealt = table.hostGame.nextSnapshot { it.actingSeatIndex != null }
        table.joinerGame.nextSnapshot { it.actingSeatIndex != null }

        // Drop the player who is NOT to act, mid-hand, allowing auto-reconnect.
        val actingClient = table.actingClient(dealt)
        val idleClient = table.other(actingClient)
        idleClient.faults!!.dropActiveConnections()

        // The connected player finishes the hand while the other is reconnecting.
        val actingSeat = dealt.actingSeatIndex!!
        table.gameOf(actingClient).submit(PlayerIntent.Fold(seatIndex = actingSeat))

        // The dropped client must reconnect and re-sync to the completed hand —
        // if reconnect/resync failed, this would time out.
        table.gameOf(idleClient).nextSnapshot { it.street == BettingRound.Complete }
        table.gameOf(actingClient).nextSnapshot { it.street == BettingRound.Complete }
    }

    @Test
    fun clientDropsMidHand_reconnects_andSeesTheAwardedPot() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()

        val dealt = table.hostGame.nextSnapshot { it.actingSeatIndex != null }
        table.joinerGame.nextSnapshot { it.actingSeatIndex != null }

        // Drop the idle (non-acting) player mid-hand. The player still at the
        // table then folds, so the dropped player is the lone non-folder — the
        // winner. The interesting seam is the ordering under fault: the award
        // (a derived stack change, not just a Complete flag) must survive the
        // drop + reconnect + resync and land on the *winner's own* view.
        val actingClient = table.actingClient(dealt)
        val idleClient = table.other(actingClient)
        idleClient.faults!!.dropActiveConnections()

        val actingSeat = dealt.actingSeatIndex!!
        table.gameOf(actingClient).submit(PlayerIntent.Fold(seatIndex = actingSeat))

        val resynced = table.gameOf(idleClient).nextSnapshot { it.street == BettingRound.Complete }

        val winnerSeat = resynced.seats.first { it.playerId == idleClient.userId }
        val folderSeat = resynced.seats.first { it.playerId == actingClient.userId }
        assertTrue(
            winnerSeat.stack > resynced.settings.startingStack,
            "the reconnected winner must see its own awarded pot (stack ${winnerSeat.stack} > ${resynced.settings.startingStack})",
        )
        assertTrue(
            folderSeat.stack < resynced.settings.startingStack,
            "the folder forfeits its posted blind",
        )
        assertEquals(
            resynced.settings.startingStack * resynced.seats.size,
            resynced.seats.sumOf { it.stack },
            "chips conserve across the fault — the award didn't mint or burn any",
        )
    }
}
