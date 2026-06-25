package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.awaitUntil
import com.cards.integration.helpers.cards
import com.cards.integration.helpers.driveActions
import com.cards.integration.helpers.seatPrivate
import com.cards.integration.helpers.stackedDeck
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * MP-13: a player who busts out, has their seat dropped from the next hand, then
 * leaves, must be cashed out their real (zero) stack — not refunded their whole
 * escrow. Once the seat is dropped the leave path can't read a live stack for
 * them, so it used to fall through to a full-escrow refund: a chip mint. Unlocked
 * by the MP-18 deck-scripting harness. Starter grant 10,000; default buy-in 5,000.
 */
class Mp13ConservationTest : IntegrationTest() {

    @Test
    fun bustedPlayer_droppedFromNextHand_thenLeaves_isNotRefundedEscrow() = integration {
        val room = seatPrivate(humanCount = 3)
        val busted = room.clients[0]

        // Seat 0 gets trash and shoves; seat 1 has aces; seat 2 folds. Seat 0 loses
        // the all-in and busts to zero, while seats 1 and 2 keep chips.
        server.scriptDeck(
            room.code,
            stackedDeck(
                holeBySeat = listOf(cards("2c 7d"), cards("As Ad"), cards("3h 8s")),
                board = cards("Ah 7c 2d 9h 3s"),
            ),
        )
        room.hostGame.startHand()
        val done = room.driveActions { seat, state ->
            when (seat) {
                0 -> PlayerIntent.AllIn(seatIndex = seat)
                2 -> PlayerIntent.Fold(seatIndex = seat)
                else -> {
                    val owed = state.currentBetThisStreet - state.seatAt(seat).contributedThisStreet
                    if (owed > 0) PlayerIntent.Call(seatIndex = seat) else PlayerIntent.Check(seatIndex = seat)
                }
            }
        }
        val buyIn = done.settings.startingStack
        assertEquals(0L, done.seats.first { it.index == 0 }.stack, "the scripted shove busts seat 0 to zero")

        // Deal the next hand: seats 1 and 2 still have chips, so it deals and DROPS
        // the busted seat 0 — now the busted player holds no seat in the live state.
        room.hostGame.requestNextHand()
        room.hostGame.nextSnapshot { it.handNumber > done.handNumber && it.actingSeatIndex != null }

        // The busted player leaves. They lost everything, so the cash-out must
        // refund 0 — their wallet stays at (grant - buyIn), not (grant - buyIn +
        // buyIn). Wait for the async cash-out to actually fire (the session closes)
        // before reading, since a correct refund of 0 doesn't move the balance.
        busted.repository.leaveRoom(room.code)
        awaitUntil(timeoutMs = 10_000) { !server.hasActiveTableSession(busted.userId) }

        assertEquals(
            10_000L - buyIn,
            server.walletBalance(busted.userId),
            "a busted player who leaves with no live seat is refunded 0, not their escrow (no mint)",
        )
    }
}
