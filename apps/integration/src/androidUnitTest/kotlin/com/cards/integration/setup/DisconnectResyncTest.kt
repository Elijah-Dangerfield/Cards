package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.seatTwoAndConnect
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **Reconnect-resync under an opponent's action.** A player who drops mid-hand
 * and reconnects must catch up to whatever happened while they were away — here
 * the opponent acts during the gap, and the returning client's next snapshot
 * reflects that, rather than replaying the stale pre-drop state. Complements
 * ChaosPlayTest (which drops during a fold) with a non-fold opponent action.
 */
class DisconnectResyncTest : IntegrationTest() {

    @Test
    fun reconnectingMidHand_seesTheOpponentsInterveningAction() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()
        var dealt = table.hostGame.nextSnapshot { it.actingSeatIndex != null }

        // Make sure it's the JOINER's turn when the host drops, so the opponent
        // has a move to make in the gap. If the host is up first, host acts to
        // pass the turn.
        if (dealt.seatAt(dealt.actingSeatIndex!!).playerId == table.host.userId) {
            val s = dealt.actingSeatIndex!!
            table.hostGame.submit(passiveIntent(dealt, s))
            dealt = table.hostGame.nextSnapshot {
                it.actingSeatIndex != null &&
                    it.seatAt(it.actingSeatIndex!!).playerId == table.joiner.userId
            }
        }
        val beforeDrop = dealt

        // Host's socket drops (transient — reconnect allowed). The joiner acts
        // while the host is away.
        table.host.faults!!.dropActiveConnections()
        val joinerSeat = beforeDrop.actingSeatIndex!!
        table.joinerGame.submit(passiveIntent(beforeDrop, joinerSeat))

        // The host's socket reconnects on its own and the next snapshot it reads
        // reflects the joiner's move — the turn moved off the joiner or the street
        // advanced; never the stale pre-drop state.
        val resync = table.hostGame.nextSnapshot()
        assertTrue(
            resync.actingSeatIndex != beforeDrop.actingSeatIndex || resync.street != beforeDrop.street,
            "after reconnect the host sees the opponent's intervening action, not the stale state",
        )
    }

    private fun passiveIntent(state: GameState, seat: Int): PlayerIntent {
        val owed = state.currentBetThisStreet - state.seatAt(seat).contributedThisStreet
        return if (owed > 0) PlayerIntent.Call(seat) else PlayerIntent.Check(seat)
    }
}
