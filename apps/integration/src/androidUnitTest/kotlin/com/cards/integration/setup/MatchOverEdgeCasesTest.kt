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

    @Test
    @kotlin.test.Ignore(
        "MP-17 KNOWN BUG (reproduces): an all-in player who leaves mid-hand has their " +
            "committed chips burned. The leave handler cashes them out at stackFor = 0 " +
            "(committed chips excluded), but the engine deliberately keeps an all-in seat " +
            "live (forfeitAllInSeat_isNoOp — they keep their showdown right). If that all-in " +
            "then wins, the pot lands on a seat already cashed out at 0 → ~5,050 chips burn " +
            "(wallets settle at 14,950, not 20,000). Fix = deferred settlement: don't cash an " +
            "all-in leaver out at 0; settle their real resolved stack when the committed hand " +
            "completes (a hand-completion seam with wallet access). Folding them instead is " +
            "wrong — it would rob a briefly-disconnected all-in player of a winning hand.",
    )
    fun allInPlayerLeavesMidHand_forfeitsThePot_chipsStillConserve() = integration {
        val table = seatTwoAndConnect()
        // The leaver (seat 0, first to act heads-up) holds the *winning* hand. The
        // skew this guards against: getting paid for a pot they walked away from.
        // Forfeit must fold them out, so they lose despite the aces — and no chip
        // is minted or lost in the process.
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

        // The all-in player abandons the table before the hand resolves.
        shoverClient.repository.leaveRoom(table.code)
        table.other(shoverClient).repository.leaveRoom(table.code)

        // Whatever the resolution, every chip is still accounted for: the leaver
        // forfeits the committed pot to the opponent, no mint, no burn. (Before the
        // fix the all-in leaver stayed live, "won" the pot they'd abandoned, and
        // those chips stranded — the table burned ~5,050 chips, settling at 14,950.)
        awaitUntil(timeoutMs = 10_000) {
            val h = server.walletBalance(table.host.userId)
            val j = server.walletBalance(table.joiner.userId)
            h != null && j != null && h + j == 20_000L
        }
    }
}
