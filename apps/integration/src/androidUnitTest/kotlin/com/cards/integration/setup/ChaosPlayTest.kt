package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.seatFor
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
    fun clientDropsMidHand_reconnects_andStillSeesHandComplete() = integration(nextHandBeatMs = 5_000L) {
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
    fun clientDropsMidHand_reconnects_andSeesTheAwardedPot() = integration(nextHandBeatMs = 5_000L) {
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

    /**
     * Simulates the "client backgrounded during opponent's turn" case from
     * Round 5 of the testing plan. The idle (non-acting) client drops + blocks
     * its socket — mirroring an OS suspending the app — while the still-online
     * opponent continues to play. When the idle client foregrounds (unblock),
     * its real reconnect machinery + the socket's `replay = 1` latest-snapshot
     * buffer must converge it on the live state without showing the stale
     * pre-background view.
     *
     * Companion to [clientDropsMidHand_reconnects_andSeesTheAwardedPot]: that
     * one tests a transient drop with reconnects allowed; this one tests a
     * *sustained* offline window during which the table state changes on the
     * server, then a resume.
     */
    @Test
    fun idleClientBackgrounded_opponentActs_idleResumesAndResyncsToLiveState() = integration(nextHandBeatMs = 5_000L) {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()

        val dealt = table.hostGame.nextSnapshot { it.actingSeatIndex != null }
        table.joinerGame.nextSnapshot { it.actingSeatIndex != null }

        // The non-acting client is the one being "backgrounded" — we want
        // server state to move while they're offline, driven by the opponent.
        val actingClient = table.actingClient(dealt)
        val idleClient = table.other(actingClient)

        // Background the idle client: drop the socket and block reconnects so
        // the offline window is sustained, not just a one-frame blip.
        idleClient.faults!!.dropAndBlock()

        // Opponent's turn moves the state — a fold ends the heads-up hand, so
        // the idle client must catch up to a Complete street on resume.
        val actingSeat = dealt.seatFor(actingClient).index
        val ack = table.gameOf(actingClient).submit(PlayerIntent.Fold(seatIndex = actingSeat))
        assertTrue(ack.accepted, "opponent's fold accepted, error=${ack.error}")

        // Confirm the server actually advanced the hand while the idle client
        // was offline — otherwise the resume case below isn't testing what we
        // think it is.
        val completed = table.gameOf(actingClient).nextSnapshot { it.street == BettingRound.Complete }

        // Foreground the idle client — reconnect succeeds, the socket's
        // retained latest-snapshot fires, and the gameplay session resyncs.
        idleClient.faults!!.blockReconnects = false

        val resynced = table.gameOf(idleClient).nextSnapshot { it.street == BettingRound.Complete }
        assertEquals(
            completed.handNumber,
            resynced.handNumber,
            "resynced view must be the same hand the opponent finished",
        )
        assertEquals(
            completed.seats.associate { it.index to it.stack },
            resynced.seats.associate { it.index to it.stack },
            "resynced stacks must match the post-action server view, not the stale pre-background view",
        )
    }
}
