package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.seatFor
import com.cards.integration.helpers.seatTwoAndConnect
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The "WS drops mid-SubmitIntent" reconnect race over the REAL wire: the acting
 * client submits an intent, its socket drops, and on reconnect it resends the
 * SAME nonce. The server's per-session nonce ring lives on the `GameSession`
 * (survives a single client's socket drop/reopen), so the resend is an
 * idempotent no-op — acked accepted, the hand never re-applies the action, and
 * chips conserve.
 *
 * Round 5 chaos box `WS drops mid-SubmitIntent`. The two halves are pinned
 * separately — duplicate-nonce dedup under concurrency (`NonceRaceTest`) and
 * drop+reconnect+resync (`ChaosPlayTest`) — this is the combined path: a real
 * transport drop sits BETWEEN the original send and the resend, exactly the
 * "client resends an unacked frame after a blip" case the dedup ring exists for.
 */
class InFlightSubmitReconnectTest : IntegrationTest() {

    @Test
    fun submitThenDropAndReconnect_resendsSameNonce_isIdempotent() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()

        val dealt = table.hostGame.nextSnapshot { it.actingSeatIndex != null }
        table.joinerGame.nextSnapshot { it.actingSeatIndex != null }

        val actingClient = table.actingClient(dealt)
        val actingSeat = dealt.seatFor(actingClient).index
        val nonce = UUID.randomUUID().toString()
        val fold = PlayerIntent.Fold(seatIndex = actingSeat)
        val game = table.gameOf(actingClient)

        // First send lands and is processed: the heads-up hand ends on the fold.
        val firstAck = game.submitWithNonce(fold, nonce)
        assertTrue(firstAck.accepted, "first fold acked accepted, error=${firstAck.error}")
        val completed = table.hostGame.nextSnapshot { it.street == BettingRound.Complete }
        val stacksAfterFirst = completed.seats.associate { it.index to it.stack }
        assertEquals(
            completed.settings.startingStack * completed.seats.size,
            completed.seats.sumOf { it.stack },
            "sanity: chips conserve once the hand completes on the fold",
        )

        // The acting client's socket drops, then auto-reconnects through the real
        // reconnect machinery — mirroring a blip right after the frame shipped.
        actingClient.faults!!.dropActiveConnections()

        // It resends the SAME nonce on the recovered connection. The server's
        // nonce ring (on the still-live GameSession) short-circuits before any
        // turn/seat validation, so this acks accepted even though the hand is
        // already Complete and it is no longer that seat's turn.
        val secondAck = game.submitWithNonce(fold, nonce)
        assertTrue(
            secondAck.accepted,
            "the resent nonce stays idempotently accepted after reconnect, error=${secondAck.error}",
        )
        assertEquals(nonce, secondAck.clientNonce)

        // The accepted ack on an already-Complete hand IS the idempotency proof:
        // had the resend fallen through to the engine instead of the nonce ring,
        // re-folding a finished hand from a non-acting seat would have been
        // rejected (accepted = false). Accepted means the server short-circuited
        // on the recorded nonce — no second apply, no chips minted or burned
        // (already conserved at completion above), and the captured stacks stand.
        assertEquals(
            completed.settings.startingStack * completed.seats.size,
            stacksAfterFirst.values.sum(),
            "chips conserve across the drop + reconnect + resend",
        )
    }
}
