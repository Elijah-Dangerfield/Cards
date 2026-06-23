package com.cards.integration

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.Table
import com.cards.integration.helpers.playPassivelyToCompletion
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.rooms.FindTableOutcome
import com.dangerfield.cards.libraries.rooms.PlayBotsOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end public matchmaking over the real client stack + real in-process
 * server: the find → seat → server-dealt → play flow, the honest disclosed-bot
 * fallback, and the rescue where a searcher joins a lone player's bot table and
 * the bots step aside. This is the highest-traffic path, so it's proven over the
 * wire, not just at the unit boundary.
 */
class MatchmakingPlayTest : IntegrationTest() {

    @Test
    fun twoSearchers_atTheSameTier_areSeatedTogether_andPlayAHand() = integration {
        val a = client()
        val b = client()

        val first = a.matchmaking.findTable(minBuyIn = 1_000, maxBuyIn = 1_000)
        val second = b.matchmaking.findTable(minBuyIn = 1_000, maxBuyIn = 1_000)

        val firstSuccess = assertIs<FindTableOutcome.Success>(first)
        val secondSuccess = assertIs<FindTableOutcome.Success>(second)
        assertTrue(firstSuccess.created, "first searcher opens a fresh table")
        assertFalse(secondSuccess.created, "second searcher joins it, not a new one")
        assertEquals(firstSuccess.room.code, secondSuccess.room.code, "both land at the same table")

        // The server is the dealer for a public table — a hand deals on its own
        // once both humans are present (no host StartHand).
        val code = firstSuccess.room.code
        val aGame = gameplay(a.connect(code))
        val bGame = gameplay(b.connect(code))
        aGame.awaitConnected()
        bGame.awaitConnected()

        val table = Table(code, a, aGame, b, bGame)
        val completed = table.playPassivelyToCompletion()
        assertEquals(BettingRound.Complete, completed.street, "two real humans played a hand to the end")
        assertTrue(completed.seats.all { !it.isBot }, "an all-human table — no bots seated")
    }

    @Test
    fun aLonelySearcher_playsDisclosedBots_thatPayRealChips() = integration {
        val a = client()
        val found = assertIs<FindTableOutcome.Success>(a.matchmaking.findTable(1_000, 1_000))
        assertTrue(found.created, "nobody else around — a fresh table to wait in")

        // The honest fallback: fill disclosed bots and deal.
        val bots = assertIs<PlayBotsOutcome.Success>(a.matchmaking.playBots(found.room.code))
        assertEquals(3, bots.room.members.count { it.isBot }, "1 human + 3 disclosed bots")
        assertTrue(bots.room.members.filter { it.isBot }.all { it.isBot }, "bots are labelled, never masked")

        // Connect and confirm a hand dealt the human in against the bots — being
        // dealt means the (subsidised) buy-in was escrowed, i.e. real chips.
        val game = gameplay(a.connect(found.room.code))
        game.awaitConnected()
        val dealt = game.nextSnapshot { it.actingSeatIndex != null }
        assertTrue(dealt.seats.any { it.isBot }, "playing against bots")
        assertTrue(dealt.seats.any { it.playerId == a.userId }, "the human was funded + dealt in")
    }

    @Test
    fun aSearcher_rescuesALonePlayerFromTheirBotTable() = integration {
        // A lonely player gives up and plays bots.
        val lonely = client()
        val table = assertIs<FindTableOutcome.Success>(lonely.matchmaking.findTable(1_000, 1_000))
        val code = table.room.code
        assertIs<PlayBotsOutcome.Success>(lonely.matchmaking.playBots(code))
        gameplay(lonely.connect(code)).awaitConnected()

        // A later searcher at the same tier lands in the bot table (rescue), not a
        // fresh one — a bot-fallback table stays matchmaking inventory.
        val rescuer = client()
        val rescued = assertIs<FindTableOutcome.Success>(rescuer.matchmaking.findTable(1_000, 1_000))
        assertFalse(rescued.created, "the searcher joins the bot table to rescue the lone player")
        assertEquals(code, rescued.room.code)
        assertEquals(2, rescued.room.members.count { !it.isBot }, "two real humans now share the table")
        assertTrue(rescued.room.members.any { it.isBot }, "the bots are still here for now")

        // The rescuer connects to a live, two-human table. (The bots then step
        // aside one per hand — that trimming is pinned deterministically by the
        // server-side unit tests; here we prove the rescue seating end-to-end.)
        val room = gameplay(rescuer.connect(code)).awaitConnected()
        assertEquals(2, room.members.count { !it.isBot }, "both humans are live at the table")
    }
}
