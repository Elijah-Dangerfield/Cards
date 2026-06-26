package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.awaitUntil
import com.cards.integration.helpers.cards
import com.cards.integration.helpers.seatTwoAndConnect
import com.cards.integration.helpers.shoveAllInHeadsUp
import com.cards.integration.helpers.stackedDeck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end coverage for the heads-up match-over (MP-14), unlocked by the
 * deck-scripting harness (MP-18). A scripted deck busts a chosen player over the
 * real wire — the exact case that had no test, and the reason the bust-freeze
 * shipped. Starter grant is 10,000; default buy-in 5,000.
 */
class MatchOverPlayTest : IntegrationTest() {

    // Seat 0 (the button — first to act and first to shove heads-up) gets trash;
    // seat 1 gets aces and the board misses seat 0, so the shover loses and busts.
    private fun bustTheShoverDeck() = stackedDeck(
        holeBySeat = listOf(cards("2c 7d"), cards("As Ad")),
        board = cards("Ah 7c 2d 9h 3s"),
    )

    @Test
    fun headsUpBust_resolvesMatchOver_andConservesChips() = integration {
        val table = seatTwoAndConnect()
        server.scriptDeck(table.code, bustTheShoverDeck())

        table.hostGame.startHand()
        val done = table.shoveAllInHeadsUp()
        assertEquals(
            1,
            done.seats.count { it.stack == 0L },
            "the scripted all-in busts exactly one player to zero",
        )

        // The grace window expires with no rebuy → the match resolves and the room
        // flips terminal. The old bug left both players frozen here forever.
        awaitUntil(timeoutMs = 10_000) { server.roomIsFinished(table.code) }

        // Both leave the finished table; chips conserve across the whole game — the
        // loser is cashed out their real 0 stack, not refunded their escrow.
        table.host.repository.leaveRoom(table.code)
        table.joiner.repository.leaveRoom(table.code)
        awaitUntil(timeoutMs = 10_000) {
            val h = server.walletBalance(table.host.userId) ?: return@awaitUntil false
            val j = server.walletBalance(table.joiner.userId) ?: return@awaitUntil false
            h + j == 20_000L
        }
    }

    @Test
    fun bustedPlayer_canRebuy_overTheWire() = integration {
        val table = seatTwoAndConnect()
        server.scriptDeck(table.code, bustTheShoverDeck())

        table.hostGame.startHand()
        val done = table.shoveAllInHeadsUp()
        val bustedSeat = done.seats.first { it.stack == 0L }

        // A busted player rebuys before the grace expires. The real server accepts
        // it (and only returns Accepted after refilling the seat to the buy-in) —
        // the path the client-side fake server can't run.
        val ack = table.gameForSeat(done, bustedSeat.index).rebuy()
        assertTrue(ack.accepted, "a busted player can rebuy over the wire: ${ack.error}")
    }
}
