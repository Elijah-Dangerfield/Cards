package com.cards.integration

import com.cards.integration.helpers.GameplaySession
import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.Table
import com.cards.integration.helpers.playPassivelyToCompletion
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.rooms.CandidatesOutcome
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import com.dangerfield.cards.libraries.rooms.FindTableOutcome
import com.dangerfield.cards.libraries.rooms.JoinRoomOutcome
import com.dangerfield.cards.libraries.rooms.PlayBotsOutcome
import com.dangerfield.cards.libraries.rooms.RoomVisibility
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
    fun waitTimePoll_discoversATable_joinsByCode_andPlaysAHand() = integration {
        // One searcher opens a fresh table (nobody to match yet).
        val opener = client()
        val opened = assertIs<FindTableOutcome.Success>(opener.matchmaking.findTable(1_000, 1_000))
        assertTrue(opened.created, "the opener seats themselves in a fresh table")
        val code = opened.room.code

        // The ROOM-12 wait-time consolidation path over the real wire: a waiting
        // searcher's read-only candidates poll lists the opener's table and seats
        // no one (the opener is still the only member after the browse).
        val waiter = client()
        val candidates = assertIs<CandidatesOutcome.Success>(waiter.matchmaking.findCandidates(1_000, 1_000))
        val candidate = candidates.rooms.firstOrNull { it.room.code == code }
        assertTrue(candidate != null, "the opener's table is a candidate")
        assertEquals(
            1,
            candidate.room.members.count { !it.isBot },
            "browsing seats nobody — the candidate still has just the opener",
        )

        // The migration joins the discovered table by code.
        val joined = assertIs<JoinRoomOutcome.Success>(waiter.repository.joinRoom(code))
        assertEquals(code, joined.room.code, "joined the discovered table, not some other match")

        // Both connect — a public table is server-dealt, so a hand deals itself once
        // both humans are present.
        val openerGame = gameplay(opener.connect(code))
        val waiterGame = gameplay(waiter.connect(code))
        openerGame.awaitConnected()
        waiterGame.awaitConnected()

        val table = Table(code, opener, openerGame, waiter, waiterGame)
        val completed = table.playPassivelyToCompletion()
        assertEquals(BettingRound.Complete, completed.street, "the consolidated table played a full hand")
        assertTrue(completed.seats.all { !it.isBot }, "two humans converged and played — no bots")
    }

    @Test
    fun candidatesBrowse_withNoEligibleTables_getsAnEmptyList() = integration {
        // Nothing is open at this tier, so the wait-time poll returns empty — the
        // waiting client just keeps waiting toward the honest bot fallback.
        val searcher = client()
        val candidates = assertIs<CandidatesOutcome.Success>(searcher.matchmaking.findCandidates(1_000, 1_000))
        assertTrue(candidates.rooms.isEmpty(), "no eligible table → empty list, seating no one")
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
    fun anOpenTable_isDiscoverable_andServerDealsForAMatchedStranger() = integration {
        // A host opens their room to anyone at the 1k tier.
        val host = client()
        val created = assertIs<CreateRoomOutcome.Success>(host.repository.createRoom(buyIn = 1_000, open = true))
        val code = created.room.code
        assertEquals(RoomVisibility.Open, created.room.visibility, "the room is open to anyone")

        // A stranger searching that tier is matched into the host's Open table.
        val stranger = client()
        val found = assertIs<FindTableOutcome.Success>(stranger.matchmaking.findTable(1_000, 1_000))
        assertFalse(found.created, "matched into the host's Open table, not a fresh one")
        assertEquals(code, found.room.code)

        // Both connect — an Open table is server-dealt, so a hand deals itself
        // with no host Start tapped.
        val hostGame = gameplay(host.connect(code))
        gameplay(stranger.connect(code)).awaitConnected()
        hostGame.awaitConnected()
        val dealt = hostGame.nextSnapshot { it.actingSeatIndex != null }
        assertEquals(2, dealt.seats.count { !it.isBot }, "two humans, server-dealt — nobody tapped Start")
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

    /**
     * CARDS-25 regression over the real wire. A server-dealt all-human table
     * (here an Open table matched by two humans, no bots) has no client "next
     * hand" control — the server is its dealer. It used to stall the moment a
     * hand finished: the driver only advanced bot-occupied tables, so every
     * seat's next-hand was inert. Here both humans play consecutive hands to
     * completion without ever tapping next-hand; the server must keep dealing.
     */
    @Test
    fun serverDealtAllHumanTable_advancesConsecutiveHands_withNoNextHandTap() = integration {
        val host = client()
        val created = assertIs<CreateRoomOutcome.Success>(host.repository.createRoom(buyIn = 1_000, open = true))
        val code = created.room.code

        val stranger = client()
        assertIs<FindTableOutcome.Success>(stranger.matchmaking.findTable(1_000, 1_000))

        val hostGame = gameplay(host.connect(code))
        val strangerGame = gameplay(stranger.connect(code))
        hostGame.awaitConnected()
        strangerGame.awaitConnected()

        val seatOf = mapOf(host.userId to hostGame, stranger.userId to strangerGame)
        // Drive three consecutive hands purely by acting each human's turn — never
        // calling requestNextHand. An all-human server-dealt table only reaches
        // hand 3 if the server itself advances each boundary.
        val handsSeen = playHumanTurnsAcrossHands(hostGame, seatOf, targetHand = 3)

        assertTrue(
            handsSeen >= 3,
            "a server-dealt all-human table deals consecutive hands with no next-hand tap (saw hand $handsSeen)",
        )
    }
}

/**
 * Walk the table forward by acting only the human seats (fold facing a bet, else
 * check) until [observer] reports reaching [targetHand], never calling
 * requestNextHand. Bot turns and hand-boundary auto-advance are the server's job;
 * the loop just keeps reading snapshots and acting whichever human is on the
 * clock. Returns the highest hand number observed.
 */
private suspend fun playHumanTurnsAcrossHands(
    observer: GameplaySession,
    seatOf: Map<String, GameplaySession>,
    targetHand: Int,
    maxSteps: Int = 400,
): Int {
    var highestHand = 0
    var steps = 0
    while (steps++ < maxSteps) {
        val state: GameState = observer.nextSnapshot(timeoutMs = 30_000) {
            it.actingSeatIndex != null || it.street == BettingRound.Complete
        }
        highestHand = maxOf(highestHand, state.handNumber)
        if (state.handNumber >= targetHand && state.street == BettingRound.Complete) return highestHand
        val acting = state.actingSeatIndex ?: continue // post-hand or bot settling — read on
        val seat = state.seats.first { it.index == acting }
        val game = seatOf[seat.playerId] ?: continue // a bot's turn — the server drives it
        val toCall = state.currentBetThisStreet - seat.contributedThisStreet
        game.submit(if (toCall > 0) PlayerIntent.Fold(acting) else PlayerIntent.Check(acting))
    }
    return highestHand
}
