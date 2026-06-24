package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.dangerfield.cards.libraries.rooms.FindTableOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

/**
 * **Matchmaking gaps beyond the happy path.** Searchers at non-overlapping tiers
 * never share a table, and a searcher matched into a table that's already
 * mid-hand lands as a member-spectator (to be dealt in at the next boundary) —
 * the matchmaking ↔ mid-hand-join seam. (Both tiers here stay within the lenient
 * 1× find-gate on the 10k starter grant.)
 */
class MatchmakingGapsTest : IntegrationTest() {

    @Test
    fun searchersAtDifferentTiers_getSeparateTables() = integration {
        val a = client()
        val b = client()

        val first = assertIs<FindTableOutcome.Success>(a.matchmaking.findTable(1_000, 1_000))
        val second = assertIs<FindTableOutcome.Success>(b.matchmaking.findTable(5_000, 5_000))

        assertTrue(first.created, "the 1k searcher opens a 1k table")
        assertTrue(second.created, "the 5k searcher opens its own table, not the 1k one")
        assertNotEquals(first.room.code, second.room.code, "non-overlapping tiers never share a table")
        assertEquals(1_000L, first.room.buyIn)
        assertEquals(5_000L, second.room.buyIn)
    }

    @Test
    fun searcherMatchedIntoAPlayingTable_landsAsMemberSpectator() = integration {
        // Two searchers form a public table and the server deals it.
        val a = client()
        val b = client()
        val opened = assertIs<FindTableOutcome.Success>(a.matchmaking.findTable(1_000, 1_000))
        val code = opened.room.code
        assertIs<FindTableOutcome.Success>(b.matchmaking.findTable(1_000, 1_000))

        val aGame = gameplay(a.connect(code)).also { it.awaitConnected() }
        gameplay(b.connect(code)).awaitConnected()
        aGame.nextSnapshot { it.actingSeatIndex != null } // the table is mid-hand (Playing)

        // A third searcher at the same tier is matched into the live table, not a
        // fresh one, and connects as a member-spectator of the in-flight hand.
        val c = client()
        val matched = assertIs<FindTableOutcome.Success>(c.matchmaking.findTable(1_000, 1_000))
        assertFalse(matched.created, "the third searcher joins the live table, not a new one")
        assertEquals(code, matched.room.code)
        assertTrue(matched.room.members.any { it.userId == c.userId }, "the searcher is now a member")

        val cGame = gameplay(c.connect(code))
        val view = cGame.nextSnapshot { it.seats.isNotEmpty() }
        assertFalse(
            view.seats.any { it.playerId == c.userId },
            "the mid-hand entrant spectates the live hand without a seat yet",
        )
    }
}
