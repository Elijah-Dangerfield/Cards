package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.seatTwoAndConnect
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * **Concurrency + ordering at the seat level.** The engine mutex must let exactly
 * one of two in-flight intents for the same seat win, and an intent that arrives
 * before there's a hand to act on must be rejected rather than corrupting state.
 */
class ConcurrencyTest : IntegrationTest() {

    @Test
    fun twoIntentsForTheSameSeat_exactlyOneIsApplied() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()
        val dealt = table.hostGame.nextSnapshot { it.actingSeatIndex != null }
        val seat = dealt.actingSeatIndex!!
        val actor = table.gameForSeat(dealt, seat)

        // Fire a Call and a Fold for the same seat without awaiting between them.
        // The engine serializes under its mutex: the first changes the state and
        // the second is then out of turn, so exactly one is accepted.
        val acks = coroutineScope {
            listOf(
                async { actor.submit(PlayerIntent.Call(seat)) },
                async { actor.submit(PlayerIntent.Fold(seat)) },
            ).awaitAll()
        }
        assertEquals(
            1,
            acks.count { it.accepted },
            "exactly one of two racing same-seat intents is applied; got ${acks.map { it.accepted }}",
        )
    }

    @Test
    fun intentBeforeAnyHandIsDealt_isRejected() = integration {
        val table = seatTwoAndConnect()
        // No StartHand yet — there's no live hand or seat to act on.
        val ack = table.hostGame.submit(PlayerIntent.Fold(seatIndex = 0))
        assertFalse(ack.accepted, "an intent with no hand in progress is rejected, not applied")
    }
}
