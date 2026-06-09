package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.seatTwoAndConnect
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlin.test.Test

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
}
