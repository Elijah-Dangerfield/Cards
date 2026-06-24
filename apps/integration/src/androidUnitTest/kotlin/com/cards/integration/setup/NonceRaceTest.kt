package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.seatFor
import com.cards.integration.helpers.seatTwoAndConnect
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The acting client fires the SAME intent under the SAME nonce TWICE at once
 * (the real "WS blip → client resends an unacked frame" race). The server's
 * per-session mutex serializes them and its nonce dedup processes the action
 * exactly once, yet acks BOTH sends accepted. State mutates once; chips
 * conserve.
 *
 * Wire-level coverage for the open Round 2 box
 * `twoClientsRace_sameIntent_serverDedupesViaNonce`. The single-send nonce
 * idempotency is pinned at the unit tier
 * (`RoomSocketGameplayRoutesTest.submitIntent_duplicateNonce_processedOnce_acksTwice`);
 * this pins the duplicate-send race over the real socket under genuine
 * concurrency.
 *
 * Why one client and not two distinct users: the server records a nonce only
 * for an *accepted* intent, after seat-ownership validation. A duplicate from a
 * non-owning second user is a wrong-seat rejection (correctly, it never burns
 * the nonce), so a clean idempotent accept is only well-defined for the same
 * actor resending — which is exactly the reconnect-resend case this guards.
 */
class NonceRaceTest : IntegrationTest() {

    @Test
    fun twoClientsRace_sameIntent_serverDedupesViaNonce() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()

        val dealt = table.hostGame.nextSnapshot { it.actingSeatIndex != null }
        table.joinerGame.nextSnapshot { it.actingSeatIndex != null }

        val actingClient = table.actingClient(dealt)
        val actingSeat = dealt.seatFor(actingClient).index
        val sharedNonce = UUID.randomUUID().toString()
        val fold = PlayerIntent.Fold(seatIndex = actingSeat)
        val game = table.gameOf(actingClient)

        // Same client resends the same nonce twice at once — a genuine race for
        // the session mutex on real threads.
        val (firstAck, secondAck) = coroutineScope {
            val a = async { game.submitWithNonce(fold, sharedNonce) }
            val b = async { game.submitWithNonce(fold, sharedNonce) }
            a.await() to b.await()
        }

        assertTrue(firstAck.accepted, "first send acked accepted, error=${firstAck.error}")
        assertTrue(secondAck.accepted, "duplicate-nonce send acked accepted (idempotent no-op)")
        assertEquals(sharedNonce, firstAck.clientNonce)
        assertEquals(sharedNonce, secondAck.clientNonce)

        // Processed once: the heads-up hand is over and chips conserved — the
        // duplicate didn't double-apply the fold or mint/burn chips.
        val complete = table.hostGame.nextSnapshot { it.street == BettingRound.Complete }
        assertEquals(
            complete.settings.startingStack * complete.seats.size,
            complete.seats.sumOf { it.stack },
            "chips conserve — the deduped duplicate intent mutated nothing twice",
        )
    }
}
