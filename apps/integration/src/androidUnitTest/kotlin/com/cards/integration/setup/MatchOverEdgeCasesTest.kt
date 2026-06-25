package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.awaitUntil
import com.cards.integration.helpers.cards
import com.cards.integration.helpers.driveActions
import com.cards.integration.helpers.seatPrivate
import com.cards.integration.helpers.seatTwoAndConnect
import com.cards.integration.helpers.stackedDeck
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The MP terminal edge cases beyond the heads-up bust (MP-14 / [MatchOverPlayTest]):
 * a multi-way table collapsing to one survivor, and a staged 3→2→1 collapse. Each
 * must resolve cleanly (the match flips terminal) with every chip conserved — the
 * scenarios `MatchOverGraceDriver.terminalBust` is meant to cover but had no test.
 *
 * Uses the deck-scripting harness (MP-18): a stacked deck busts chosen seats on a
 * single all-in, the only way to force a bust over the real wire.
 */
class MatchOverEdgeCasesTest : IntegrationTest() {

    // Three seats all-in; seat 2 holds aces and the board gives trip aces, so
    // seats 0 and 1 both bust on the one hand → a single survivor.
    private fun seat2ScoopsThreeWayDeck() = stackedDeck(
        holeBySeat = listOf(cards("2c 7d"), cards("3c 8d"), cards("As Ad")),
        board = cards("Ah 7c 2s 9h 3d"),
    )

    @Test
    fun threeWayBustToOneSurvivor_resolvesMatchOver_andConservesChips() = integration {
        val room = seatPrivate(humanCount = 3, maxSeats = 3)
        server.scriptDeck(room.code, seat2ScoopsThreeWayDeck())

        room.hostGame.startHand()
        // Everyone jams; the scripted board busts two of the three.
        val done = room.driveActions { seat, _ -> PlayerIntent.AllIn(seatIndex = seat) }
        assertEquals(
            2,
            done.seats.count { it.stack == 0L },
            "the scripted three-way all-in busts exactly two players to zero",
        )

        // One survivor + two busted humans = terminal. The grace expires with no
        // rebuy and the room flips finished — it must not wedge on >1 busted seat.
        awaitUntil(timeoutMs = 10_000) { server.roomIsFinished(room.code) }

        // Everyone leaves; chips conserve across the whole game (3 × 5,000 +
        // 3 × untouched 5,000 starter remainder = 30,000 across the three wallets).
        room.clients.forEach { it.repository.leaveRoom(room.code) }
        awaitUntil(timeoutMs = 10_000) {
            val balances = room.clients.map { server.walletBalance(it.userId) }
            balances.all { it != null } && balances.filterNotNull().sum() == 30_000L
        }
    }

    // NOTE: the "both players leave during a live all-in" case is NOT covered here.
    // When every client vanishes mid-hand there's no live socket to process the
    // second leave's forfeit (which would resolve the hand) or to collect the
    // departed all-in leaver's deferred settlement, so it falls to the boot-sweep
    // backstop rather than settling in-session. Closing it cleanly means resolving
    // the live hand + settling pending leavers from RoomTeardownCoordinator (which
    // owns the wallet at room close) — tracked under MP-17.

    @Test
    fun allInWinnerLeavesMidHand_isSettledOnShowdown_chipsConserve() = integration {
        val table = seatTwoAndConnect()
        // The leaver (seat 0, first to act heads-up) holds the *winning* hand and
        // shoves all-in, then abandons the table before the showdown. The engine
        // keeps an all-in seat live (it can't be folded — it keeps its showdown
        // right), so the leaver still wins. Their winnings must reach their wallet:
        // before the fix they were cashed out at 0 on leave and the won pot burned
        // (~5,050 chips, the table settling at 14,950 instead of 20,000).
        server.scriptDeck(
            table.code,
            stackedDeck(
                holeBySeat = listOf(cards("As Ad"), cards("2c 7d")),
                board = cards("Ah 9c 4s Kd 6h"),
            ),
        )

        table.hostGame.startHand()
        val dealt = table.hostGame.nextSnapshot { it.actingSeatIndex != null }
        val shover = dealt.actingSeatIndex!!
        val shoverClient = table.actingClient(dealt)
        val stayer = table.other(shoverClient)
        val stayerGame = table.gameOf(stayer)
        val stayerSeat = dealt.seats.first { it.playerId == stayer.userId }.index
        table.gameForSeat(dealt, shover).submit(PlayerIntent.AllIn(seatIndex = shover))

        // The all-in player leaves before the opponent responds. Their cash-out is
        // deferred — their committed chips are still live.
        shoverClient.repository.leaveRoom(table.code)

        // The opponent (still connected) calls the all-in → the board runs out and
        // the departed all-in winner is settled at their real resolved stack when
        // the hand completes. Wait for the action to actually be on the stayer
        // (the shove advances it off the shover) before calling.
        stayerGame.nextSnapshot { it.actingSeatIndex == stayerSeat }
        val callAck = stayerGame.submit(PlayerIntent.Call(seatIndex = stayerSeat))
        check(callAck.accepted) { "stayer's call rejected: ${callAck.error}" }

        // The busted opponent leaves too; chips conserve end-to-end — the leaver is
        // paid the pot they won (settled when the showdown completed), the opponent
        // cashes out their real 0. Before the fix the leaver's won pot burned and
        // the table settled at 14,950.
        stayer.repository.leaveRoom(table.code)
        awaitUntil(timeoutMs = 10_000) {
            val h = server.walletBalance(table.host.userId)
            val j = server.walletBalance(table.joiner.userId)
            h != null && j != null && h + j == 20_000L
        }
    }
}
