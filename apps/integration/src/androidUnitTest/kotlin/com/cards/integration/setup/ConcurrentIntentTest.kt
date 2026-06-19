package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.seatFor
import com.cards.integration.helpers.seatTwoAndConnect
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two real clients fire intents at the SAME live hand from real threads at the
 * same moment. The server's per-session mutex must serialize them so exactly one
 * mutates state — the actor's — and the table stays consistent regardless of which
 * frame the server happens to process first.
 *
 * Wire-level coverage for the Round 5 "concurrent intents from multiple clients on
 * the same hand" box: the unit tier pins the mutex (`GameSessionTest`); this pins
 * it over the real socket against the real engine, under genuine concurrency.
 *
 * Determinism note: the non-actor's racing intent is its OWN-seat fold while it is
 * NOT to act. Whichever order the server picks, that intent is illegal — out of
 * turn if it lands before the actor folds, and against a `Complete` hand if it
 * lands after (the actor's fold ends a heads-up hand). So the non-actor is always
 * rejected without depending on scheduling.
 */
class ConcurrentIntentTest : IntegrationTest() {

    @Test
    fun twoClientsActConcurrently_mutexSerializes_onlyTheActorMutates() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()

        val dealt = table.hostGame.nextSnapshot { it.actingSeatIndex != null }
        table.joinerGame.nextSnapshot { it.actingSeatIndex != null }

        val actingClient = table.actingClient(dealt)
        val idleClient = table.other(actingClient)
        val actingSeat = dealt.seatFor(actingClient).index
        val idleSeat = dealt.seatFor(idleClient).index

        // Both fire at once on real threads — a genuine race for the session mutex.
        val (actorAck, idleAck) = coroutineScope {
            val actor = async {
                table.gameOf(actingClient).submit(PlayerIntent.Fold(seatIndex = actingSeat))
            }
            val idle = async {
                table.gameOf(idleClient).submit(PlayerIntent.Fold(seatIndex = idleSeat))
            }
            actor.await() to idle.await()
        }

        assertTrue(actorAck.accepted, "the actor's fold must be accepted, error=${actorAck.error}")
        assertFalse(idleAck.accepted, "the non-actor's concurrent fold must be rejected")

        // Exactly one mutation landed: the heads-up hand is over and chips conserved.
        val complete = table.hostGame.nextSnapshot { it.street == BettingRound.Complete }
        assertEquals(
            complete.settings.startingStack * complete.seats.size,
            complete.seats.sumOf { it.stack },
            "chips conserve — the rejected concurrent intent didn't mint or burn any",
        )
    }
}
