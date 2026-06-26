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

    // After seat 2 leaves, seats 0 and 1 remain (index order); seat 0 holds aces
    // and the board pairs them, busting seat 1 on the one all-in → a single survivor.
    private fun seat0BustsSeat1HeadsUpDeck() = stackedDeck(
        holeBySeat = listOf(cards("As Ad"), cards("2c 7d")),
        board = cards("Ah 9c 4s Kd 6h"),
    )

    @Test
    fun stagedCollapse_threeToTwoToOne_resolvesMatchOver_andConservesChips() = integration {
        // The collapse arrives one seat at a time, not in a single multi-way bust:
        // 3 members → a leave drops it to 2 → an all-in busts one to a lone survivor.
        // Each transition must settle cleanly (no wedge on the intermediate 2-handed
        // table, a clean match-over on the final bust) with every chip conserved.
        val room = seatPrivate(humanCount = 3, maxSeats = 3)
        server.scriptDeck(room.code, seat0BustsSeat1HeadsUpDeck())

        // Stage 1 (3 → 2): the third player leaves before any hand is dealt. Their
        // untouched buy-in cashes back; the table is now heads-up.
        val leaver = room.clients[2]
        leaver.repository.leaveRoom(room.code)
        room.hostGame.awaitRoom(timeoutMs = 10_000) { it.members.size == 2 }

        // Stage 2 (2 → 1): the remaining two jam; the scripted board busts seat 1,
        // leaving seat 0 as the sole survivor.
        room.hostGame.startHand()
        val done = room.driveActions { seat, _ -> PlayerIntent.AllIn(seatIndex = seat) }
        assertEquals(
            1,
            done.seats.count { it.stack == 0L },
            "the heads-up all-in busts exactly one of the two remaining players",
        )

        // One survivor + one busted human = terminal heads-up match-over. The grace
        // expires with no rebuy and the room flips finished.
        awaitUntil(timeoutMs = 10_000) { server.roomIsFinished(room.code) }

        // Everyone leaves; chips conserve across the whole game (3 starter grants of
        // 10,000 = 30,000 across the three wallets, regardless of who won the pot).
        room.clients.forEach { it.repository.leaveRoom(room.code) }
        awaitUntil(timeoutMs = 10_000) {
            val balances = room.clients.map { server.walletBalance(it.userId) }
            balances.all { it != null } && balances.filterNotNull().sum() == 30_000L
        }
    }

    @Test
    fun bothPlayersLeaveDuringAllIn_settledAtTeardown_chipsConserve() = integration {
        // Seat 0 shoves all-in, then BOTH players abandon the table — the shover
        // (deferred), then the seat facing the shove (which folds, resolving the
        // hand in the shover's favour). With no live socket left, the shover's
        // deferred settlement can't be collected in-session; the room-teardown must
        // settle every still-open session at its resolved stack so no chip is lost.
        val table = seatTwoAndConnect()
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
        table.gameForSeat(dealt, shover).submit(PlayerIntent.AllIn(seatIndex = shover))

        shoverClient.repository.leaveRoom(table.code)
        table.other(shoverClient).repository.leaveRoom(table.code)

        awaitUntil(timeoutMs = 10_000) {
            val h = server.walletBalance(table.host.userId)
            val j = server.walletBalance(table.joiner.userId)
            h != null && j != null && h + j == 20_000L
        }
    }

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
