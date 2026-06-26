package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.cards
import com.cards.integration.helpers.driveActions
import com.cards.integration.helpers.seatPrivate
import com.cards.integration.helpers.stackedDeck
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * MP-13, crash edge. A player busts out, is dropped from the next hand, then the
 * server CRASHES before they leave — so the boot recovery sweep, not a live leave,
 * settles their stranded session. The live-leave half is pinned by
 * [Mp13ConservationTest]; this is the open end: the busted player's last-known
 * stack lived only in the in-memory `GameSession`, gone after the crash, so the
 * sweep rehydrated only the snapshot — which has no seat for them — and fell
 * through to a full-escrow refund (a mint). Persisting `lastKnownStacks` in the
 * snapshot is what lets the sweep cash them out their real 0.
 *
 * Starter grant 10,000; default buy-in 5,000.
 */
class Mp13CrashRecoveryConservationTest : IntegrationTest() {

    @Test
    fun bustedPlayer_droppedFromNextHand_thenCrash_sweepCashesOutZero_noMint() = integration {
        val room = seatPrivate(humanCount = 3)
        val busted = room.clients[0]

        // Seat 0 shoves trash into seat 1's aces and busts to zero; seat 2 folds.
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
        // the busted seat 0 — the snapshot persisted here no longer seats them, but
        // it now carries their persisted last-known stack of 0 (MP-13).
        room.hostGame.requestNextHand()
        room.hostGame.nextSnapshot { it.handNumber > done.handNumber && it.actingSeatIndex != null }

        // Crash: drop the engine + its in-memory registry (and its lastKnownStacks
        // map), keeping the durable snapshot + the still-open table session — exactly
        // what a real bounce leaves in Postgres. Seat 0 never left, so their session
        // is still open and the boot sweep must settle it.
        server.restart()

        val settled = server.sweepAbandonedSessions()
        assertEquals(3, settled, "the boot sweep settles all three stranded sessions")

        assertEquals(
            10_000L - buyIn,
            server.walletBalance(busted.userId),
            "the swept busted player is cashed out 0 from the persisted last-known stack, not refunded their escrow (no mint)",
        )
    }
}
