package com.cards.integration.setup

import com.cards.integration.helpers.GameplaySession
import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.createAndJoin
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * Drives a full heads-up hand over a [com.cards.integration.helpers.LatencyTransport]
 * that adds 200ms each way (a 400ms+ RTT). Pins two things the latency transport
 * exists to prove:
 *
 *  - the ack-wait machinery tolerates a slow wire — a submit round-trip clears
 *    well within the client's intent timeout, it just takes the RTT; and
 *  - the double-submit dedupe guard still dedupes when the resend overlaps the
 *    slow first send (the real reconnect-resend race, now stretched in time):
 *    both sends ack accepted, the fold applies once, chips conserve.
 */
class LatencyPlayTest : IntegrationTest() {

    @Test
    fun submitUnderLatency_roundTripsAndDedupesDuplicateNonce() = integration {
        val host = client(latencyMs = ONE_WAY_DELAY_MS)
        val joiner = client(latencyMs = ONE_WAY_DELAY_MS)
        val code = createAndJoin(host, joiner)
        val hostGame = gameplay(host.connect(code))
        val joinerGame = gameplay(joiner.connect(code))
        hostGame.awaitConnected()
        joinerGame.awaitConnected()

        hostGame.startHand()
        val dealt = hostGame.nextSnapshot { it.actingSeatIndex != null }
        joinerGame.nextSnapshot { it.actingSeatIndex != null }

        val actingSeatIndex = dealt.actingSeatIndex!!
        val actingPlayerId = dealt.seatAt(actingSeatIndex).playerId
        val actingGame: GameplaySession =
            if (actingPlayerId == host.userId) hostGame else joinerGame

        val sharedNonce = UUID.randomUUID().toString()
        val fold = PlayerIntent.Fold(seatIndex = actingSeatIndex)

        val elapsed = measureTime {
            val (firstAck, secondAck) = coroutineScope {
                val a = async { actingGame.submitWithNonce(fold, sharedNonce) }
                val b = async { actingGame.submitWithNonce(fold, sharedNonce) }
                a.await() to b.await()
            }
            assertTrue(firstAck.accepted, "first send acked accepted under latency, error=${firstAck.error}")
            assertTrue(secondAck.accepted, "duplicate-nonce send acked accepted (idempotent no-op) under latency")
            assertEquals(sharedNonce, firstAck.clientNonce)
            assertEquals(sharedNonce, secondAck.clientNonce)
        }

        assertTrue(
            elapsed.inWholeMilliseconds >= ONE_WAY_DELAY_MS * 2,
            "a submit round-trip honored the simulated RTT (>= ${ONE_WAY_DELAY_MS * 2}ms), took ${elapsed.inWholeMilliseconds}ms",
        )

        val complete = hostGame.nextSnapshot { it.street == BettingRound.Complete }
        assertEquals(
            complete.settings.startingStack * complete.seats.size,
            complete.seats.sumOf { it.stack },
            "chips conserve — the deduped duplicate fold mutated nothing twice under latency",
        )
    }

    private companion object {
        const val ONE_WAY_DELAY_MS = 200L
    }
}
