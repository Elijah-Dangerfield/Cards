package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.awaitUntil
import com.cards.integration.helpers.playPassivelyToCompletion
import com.cards.integration.helpers.seatTwoAndConnect
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import com.dangerfield.cards.libraries.rooms.JoinRoomOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * **Real-chip escrow over the wire.** Buy-in moves wallet → table stack at the
 * deal and the leftover stack moves back on leave; chips only ever move between
 * players, never created or destroyed. These read server wallet balances
 * directly ([InProcessServer.walletBalance]) to assert the escrow ledger, which
 * no client API exposes. Starter grant is 10,000; default buy-in 5,000.
 */
class ChipEconomyPlayTest : IntegrationTest() {

    @Test
    fun startHand_escrowsExactlyTheBuyIn_fromEachHumanWallet() = integration {
        val table = seatTwoAndConnect()
        // (Wallets are lazily created on first escrow touch, so they read null
        // until the deal; the post-deal balance below proves the 10,000 grant.)

        table.hostGame.startHand()
        val dealt = table.hostGame.nextSnapshot { it.actingSeatIndex != null }
        val buyIn = dealt.settings.startingStack

        // The wallet is debited exactly the buy-in from the 10,000 starter grant
        // for each seated human...
        assertEquals(10_000L - buyIn, server.walletBalance(table.host.userId), "host buy-in escrowed")
        assertEquals(10_000L - buyIn, server.walletBalance(table.joiner.userId), "joiner buy-in escrowed")
        // ...and the engine seated each with exactly that buy-in (stack + the
        // blind already committed to the pot sums back to the buy-in).
        dealt.seats.forEach { seat ->
            assertEquals(
                buyIn,
                seat.stack + seat.contributedThisStreet,
                "seat ${seat.index} was seated with the full escrowed buy-in",
            )
        }
    }

    @Test
    fun leavingPlayer_isCashedOutTheirCurrentStack() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()
        val dealt = table.hostGame.nextSnapshot { it.actingSeatIndex != null }

        val joinerAfterDeal = server.walletBalance(table.joiner.userId)!!
        val joinerStack = dealt.seats.first { it.playerId == table.joiner.userId }.stack

        // The joiner leaves; the host remains, so the leave delta cashes the
        // joiner out their live stack (chips committed to the pot are forfeit).
        table.joiner.repository.leaveRoom(table.code)
        awaitUntil {
            server.walletBalance(table.joiner.userId) == joinerAfterDeal + joinerStack
        }
    }

    @Test
    fun fullHandThenBothLeave_conservesEveryChip() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()
        table.playPassivelyToCompletion()

        // Both leave: the first via the leave delta, the LAST via the room-teardown
        // coordinator (which cashes out the final seated member when the room GCs).
        table.host.repository.leaveRoom(table.code)
        table.joiner.repository.leaveRoom(table.code)

        // No chip is created or destroyed across a full hand + teardown: the two
        // wallets sum back to 2× the starter grant. (The pot just moved chips
        // between the two stacks; both came home.)
        awaitUntil(timeoutMs = 15_000) {
            val h = server.walletBalance(table.host.userId) ?: return@awaitUntil false
            val j = server.walletBalance(table.joiner.userId) ?: return@awaitUntil false
            h + j == 20_000L
        }
    }

    @Test
    fun leavingTwice_doesNotDoubleCreditTheWallet() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()
        val dealt = table.hostGame.nextSnapshot { it.actingSeatIndex != null }
        val joinerAfterDeal = server.walletBalance(table.joiner.userId)!!
        val joinerStack = dealt.seats.first { it.playerId == table.joiner.userId }.stack

        table.joiner.repository.leaveRoom(table.code)
        awaitUntil { server.walletBalance(table.joiner.userId) == joinerAfterDeal + joinerStack }
        val afterFirstLeave = server.walletBalance(table.joiner.userId)!!

        // A second leave (a re-issued DELETE, a dead back button) is synchronous
        // over HTTP, so by the time it returns any erroneous re-credit would have
        // landed — the balance must be unchanged (cashOut is keyed per session).
        table.joiner.repository.leaveRoom(table.code)
        assertEquals(
            afterFirstLeave,
            server.walletBalance(table.joiner.userId),
            "a duplicate leave does not double-credit the cash-out",
        )
    }

    @Test
    fun joiningATableYouCannotAfford_isRejectedOverBalance() = integration {
        // Host opens a room whose buy-in exceeds the 10,000 starter grant. (The
        // host needn't afford it to *create* it — only joiners are gated.)
        val host = client()
        val created = assertIs<CreateRoomOutcome.Success>(host.repository.createRoom(buyIn = 20_000))

        val pauper = client()
        val outcome = pauper.repository.joinRoom(created.room.code)
        assertIs<JoinRoomOutcome.OverBalance>(outcome)
        // And the wallet is untouched — no escrow on a refused join.
        assertEquals(10_000L, server.walletBalance(pauper.userId), "a refused join debits nothing")
    }

    @Test
    fun startingStack_matchesTheRoomsCustomBuyIn() = integration {
        val host = client()
        val joiner = client()
        val created = assertIs<CreateRoomOutcome.Success>(host.repository.createRoom(buyIn = 1_000))
        val code = created.room.code
        assertIs<JoinRoomOutcome.Success>(joiner.repository.joinRoom(code))
        val hostGame = gameplay(host.connect(code)).also { it.awaitConnected() }
        gameplay(joiner.connect(code)).awaitConnected()
        hostGame.awaitRoom { it.members.size == 2 }

        hostGame.startHand()
        val dealt = hostGame.nextSnapshot { it.actingSeatIndex != null }
        assertEquals(1_000L, dealt.settings.startingStack, "settings carry the custom buy-in")
        dealt.seats.forEach { seat ->
            assertEquals(
                1_000L,
                seat.stack + seat.contributedThisStreet,
                "each seat is stacked to the custom buy-in",
            )
        }
    }
}
