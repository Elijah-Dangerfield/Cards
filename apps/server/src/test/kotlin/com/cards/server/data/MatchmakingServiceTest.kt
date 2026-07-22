package com.dangerfield.cards.server.data

import com.dangerfield.cards.libraries.bots.BotDifficulty
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.gameplay.BuyInTier
import com.dangerfield.cards.server.domain.CreateResult
import com.dangerfield.cards.server.domain.EntryBar
import com.dangerfield.cards.server.domain.MatchmakingResult
import com.dangerfield.cards.server.domain.RoomService
import com.dangerfield.cards.server.domain.RoomVisibility
import com.dangerfield.cards.server.domain.SYSTEM_HOST_USER_ID
import com.dangerfield.cards.server.domain.UserId
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Public-matchmaking core ([RoomService.findOrJoinPublic]) + buy-in bucketing.
 * Find-or-create: seat into the best eligible Open/Public room in range, else
 * open a fresh Public table — never any bots at this stage.
 */
@OptIn(ExperimentalTime::class)
class MatchmakingServiceTest {

    private val clock = object : Clock {
        private var t = 1_700_000_000_000L
        // Monotonic so "prefer oldest on a human-count tie" is deterministic.
        override fun now(): Instant = Instant.fromEpochMilliseconds(t).also { t += 1_000 }
    }

    private fun service() = InMemoryRoomService(clock = clock, random = Random(0L))
    private fun user() = UserId(UUID.randomUUID())
    private val noBlocks = emptySet<UserId>()

    @Test
    fun find_withNoRooms_createsAFreshPublicTable_seatedWithJustThePlayer_noBots() = runTest {
        val svc = service()
        val me = user()

        val result = svc.findOrJoinPublic(me, "Alice", minBuyIn = 1_000, maxBuyIn = 100_000, blockedUserIds = noBlocks)

        val created = assertIs<MatchmakingResult.Created>(result)
        assertEquals(RoomVisibility.Public, created.room.visibility)
        assertEquals(SYSTEM_HOST_USER_ID, created.room.hostUserId, "public tables use the synthetic system host")
        assertEquals(1, created.room.members.size)
        assertEquals(me, created.room.members.single().userId)
        assertFalse(created.room.members.any { it.isBot }, "no bots before the disclosed fallback")
        assertTrue(created.room.buyIn in BuyInTier.Canonical, "new table snaps to a canonical tier")
    }

    @Test
    fun secondSearcher_inOverlappingRange_joinsTheFirstsTable_convergence() = runTest {
        val svc = service()
        val first = svc.findOrJoinPublic(user(), "Alice", 1_000, 100_000, noBlocks)
        val firstCode = assertIs<MatchmakingResult.Created>(first).room.code

        val second = svc.findOrJoinPublic(user(), "Bob", 1_000, 100_000, noBlocks)

        val joined = assertIs<MatchmakingResult.Joined>(second)
        assertEquals(firstCode, joined.room.code, "overlapping ranges converge on one table")
        assertEquals(2, joined.room.members.size)
    }

    @Test
    fun find_prefersTheRoomWithMoreRealHumans() = runTest {
        val svc = service()
        // Table A: created by matchmaking, then a second searcher joins → 2 humans.
        val a = assertIs<MatchmakingResult.Created>(svc.findOrJoinPublic(user(), "A1", 5_000, 5_000, noBlocks)).room
        svc.findOrJoinPublic(user(), "A2", 5_000, 5_000, noBlocks)
        // Table B: a human-hosted Open table at the same tier → 1 human.
        val b = assertIs<CreateResult.Success>(
            svc.create(hostUserId = user(), hostName = "B1", buyIn = 5_000, visibility = RoomVisibility.Open),
        ).room

        // A new searcher should land on the livelier table (A, 2 humans), not B (1).
        val pick = assertIs<MatchmakingResult.Joined>(svc.findOrJoinPublic(user(), "C", 5_000, 5_000, noBlocks))
        assertEquals(a.code, pick.room.code, "joins the livelier table")
        assertEquals(1, b.members.size)
    }

    @Test
    fun find_skipsARoomContainingABlockedMember() = runTest {
        val svc = service()
        val enemy = user()
        // A table whose only member is someone I've blocked.
        assertIs<MatchmakingResult.Created>(svc.findOrJoinPublic(enemy, "Enemy", 5_000, 5_000, noBlocks))

        // I search with `enemy` in my blocked set → must not be seated with them.
        val result = svc.findOrJoinPublic(user(), "Me", 5_000, 5_000, blockedUserIds = setOf(enemy))

        val created = assertIs<MatchmakingResult.Created>(result)
        assertFalse(created.room.members.any { it.userId == enemy }, "never co-seated with a blocked user")
    }

    @Test
    fun openHumanHostedRoom_isDiscoverable_byTheMatchmaker() = runTest {
        val svc = service()
        val host = user()
        val open = assertIs<CreateResult.Success>(
            svc.create(hostUserId = host, hostName = "Host", buyIn = 5_000, visibility = RoomVisibility.Open),
        ).room

        val result = svc.findOrJoinPublic(user(), "Stranger", 5_000, 5_000, noBlocks)

        val joined = assertIs<MatchmakingResult.Joined>(result)
        assertEquals(open.code, joined.room.code, "an 'open to anyone' room takes a matchmade stranger")
    }

    @Test
    fun privateRoom_isNotDiscoverable_matchmakerCreatesItsOwnTable() = runTest {
        val svc = service()
        val host = user()
        val priv = assertIs<CreateResult.Success>(
            svc.create(hostUserId = host, hostName = "Host", buyIn = 5_000, visibility = RoomVisibility.Private),
        ).room

        val result = svc.findOrJoinPublic(user(), "Stranger", 5_000, 5_000, noBlocks)

        val created = assertIs<MatchmakingResult.Created>(result)
        assertTrue(created.room.code != priv.code, "a private room is never matchmaking inventory")
    }

    @Test
    fun find_isIdempotent_forAnAlreadySeatedSearcher() = runTest {
        val svc = service()
        val me = user()
        val first = assertIs<MatchmakingResult.Created>(svc.findOrJoinPublic(me, "Me", 5_000, 5_000, noBlocks))

        val again = svc.findOrJoinPublic(me, "Me", 5_000, 5_000, noBlocks)

        val joined = assertIs<MatchmakingResult.Joined>(again)
        assertEquals(first.room.code, joined.room.code, "a re-search returns the table I'm already at")
        assertEquals(1, joined.room.members.size, "no duplicate seat")
    }

    @Test
    fun find_skipsAFullRoom_andOpensANewOne_neverOverfilling() = runTest {
        val svc = service()
        // Fill a single tier table to capacity.
        val seed = assertIs<MatchmakingResult.Created>(svc.findOrJoinPublic(user(), "P0", 5_000, 5_000, noBlocks))
        val code = seed.room.code
        repeat(RoomService.MAX_SEATS - 1) { i ->
            svc.findOrJoinPublic(user(), "P${i + 1}", 5_000, 5_000, noBlocks)
        }
        val full = svc.find(code)!!
        assertEquals(RoomService.MAX_SEATS, full.members.size, "table filled to capacity")

        // The next searcher must NOT overfill — a fresh table opens instead.
        val overflow = svc.findOrJoinPublic(user(), "Overflow", 5_000, 5_000, noBlocks)
        val created = assertIs<MatchmakingResult.Created>(overflow)
        assertTrue(created.room.code != code, "a full table is skipped; a new one opens")
        assertEquals(1, created.room.members.size)
    }

    @Test
    fun lonelySearcher_oneTierBelow_isRescuedOntoTheWaitingTable() = runTest {
        val svc = service()
        // Someone is waiting alone at 1k with no in-range table for a 5k searcher.
        val waiting = assertIs<MatchmakingResult.Created>(svc.findOrJoinPublic(user(), "Waiter", 1_000, 1_000, noBlocks))

        val result = svc.findOrJoinPublic(user(), "Arriver", 5_000, 5_000, noBlocks)

        val joined = assertIs<MatchmakingResult.Joined>(result)
        assertEquals(waiting.room.code, joined.room.code, "rescued onto the lonely table instead of minting a new one")
        assertEquals(2, joined.room.members.size)
        assertEquals(1_000L, joined.room.buyIn, "seated at the incumbent's affordable stake")
    }

    @Test
    fun lonelySearchers_moreThanOneTierApart_getSeparateTables() = runTest {
        val svc = service()
        val low = assertIs<MatchmakingResult.Created>(svc.findOrJoinPublic(user(), "Low", 1_000, 1_000, noBlocks))
        // 25k is two canonical steps above 1k — too far to merge two waiting players.
        val high = svc.findOrJoinPublic(user(), "High", 25_000, 25_000, noBlocks)

        val created = assertIs<MatchmakingResult.Created>(high)
        assertTrue(created.room.code != low.room.code, "stakes more than one tier apart never merge")
        assertEquals(25_000L, created.room.buyIn)
    }

    @Test
    fun lonelySearcher_isNeverRescuedAboveTheirBuyInCeiling() = runTest {
        val svc = service()
        // A lonely human waiting at 5k.
        val rich = assertIs<MatchmakingResult.Created>(svc.findOrJoinPublic(user(), "Rich", 5_000, 5_000, noBlocks))
        // A searcher who'll only sit for 1k must not be pulled up to the 5k table.
        val result = svc.findOrJoinPublic(user(), "Thrifty", 1_000, 1_000, noBlocks)

        val created = assertIs<MatchmakingResult.Created>(result)
        assertTrue(created.room.code != rich.room.code, "never seated above the buy-in ceiling asked for")
        assertEquals(1_000L, created.room.buyIn)
    }

    @Test
    fun rescue_skipsALonelyRoomWithABlockedMember() = runTest {
        val svc = service()
        val enemy = user()
        // The only lonely table one tier away is hosted by someone I've blocked.
        assertIs<MatchmakingResult.Created>(svc.findOrJoinPublic(enemy, "Enemy", 1_000, 1_000, noBlocks))

        val result = svc.findOrJoinPublic(user(), "Me", 5_000, 5_000, blockedUserIds = setOf(enemy))

        val created = assertIs<MatchmakingResult.Created>(result)
        assertFalse(created.room.members.any { it.userId == enemy }, "rescue never co-seats a blocked user")
        assertEquals(5_000L, created.room.buyIn)
    }

    @Test
    fun find_outOfRangeRoom_isSkipped() = runTest {
        val svc = service()
        // An existing table at 1,000.
        val low = assertIs<MatchmakingResult.Created>(svc.findOrJoinPublic(user(), "Low", 1_000, 1_000, noBlocks))
        // A searcher who only wants high stakes must not land on the 1,000 table.
        val result = svc.findOrJoinPublic(user(), "High", 100_000, 100_000, noBlocks)
        val created = assertIs<MatchmakingResult.Created>(result)
        assertTrue(created.room.code != low.room.code)
        assertEquals(100_000, created.room.buyIn)
    }

    @Test
    fun find_seatsIntoAPlayingRoom_forMidHandJoin() = runTest {
        val svc = service()
        val first = assertIs<MatchmakingResult.Created>(svc.findOrJoinPublic(user(), "A", 5_000, 5_000, noBlocks)).room
        svc.findOrJoinPublic(user(), "B", 5_000, 5_000, noBlocks) // now 2 humans
        svc.markPlaying(first.code) // the table is mid-game

        // A later searcher is matched INTO the live table (joins to be dealt in
        // at the next hand) rather than spun off onto a fresh one.
        val third = svc.findOrJoinPublic(user(), "C", 5_000, 5_000, noBlocks)

        val joined = assertIs<MatchmakingResult.Joined>(third)
        assertEquals(first.code, joined.room.code, "matched into the in-progress table")
        assertEquals(3, joined.room.members.size)
    }

    @Test
    fun find_isIdempotentOnlyWithinTheRequestedRange_notAcrossTiers() = runTest {
        val svc = service()
        val me = user()
        // Seated at the 1,000 tier from a prior (abandoned-but-not-yet-reaped) search.
        val low = assertIs<MatchmakingResult.Created>(svc.findOrJoinPublic(me, "Me", 1_000, 1_000, noBlocks))

        // A fresh search at a *different* tier must not hand back the stale low table.
        val high = svc.findOrJoinPublic(me, "Me", 100_000, 100_000, noBlocks)

        val created = assertIs<MatchmakingResult.Created>(high)
        assertTrue(created.room.code != low.room.code, "a cross-tier search never returns the stale seat")
        assertEquals(100_000, created.room.buyIn, "the new table is in the range actually asked for")
    }

    @Test
    fun find_atTheDefaultBand_withAStarterGrant_opensAnAffordableAnchorTable() = runTest {
        val svc = service()
        // The client's default search band (500..2,000) with a fresh 10k grant must
        // open a table the 4× entry bar clears — the affordable-matchmaking fix.
        val result = svc.findOrJoinPublic(user(), "Fresh", 500, 2_000, noBlocks, callerBalance = 10_000)

        val created = assertIs<MatchmakingResult.Created>(result)
        assertEquals(1_000L, created.room.buyIn, "snaps to the affordable anchor, not the 5k wire default")
        assertTrue(EntryBar.canSit(10_000, created.room.buyIn), "the founder can fund the table they opened")
    }

    @Test
    fun find_skipsAnUnaffordableInRangeTable_andOpensAnAffordableOne() = runTest {
        val svc = service()
        // A 5,000 table exists (opened by a wealthy player).
        val rich = assertIs<MatchmakingResult.Created>(
            svc.findOrJoinPublic(user(), "Rich", 5_000, 5_000, noBlocks, callerBalance = 100_000),
        ).room
        // A fresh-grant searcher browses a wide range that includes 5k, but can't
        // clear the 4× bar for it (needs 20k). They must NOT be auto-seated there;
        // an affordable anchor table opens instead.
        val result = svc.findOrJoinPublic(user(), "Fresh", 1_000, 100_000, noBlocks, callerBalance = 10_000)

        val created = assertIs<MatchmakingResult.Created>(result)
        assertTrue(created.room.code != rich.code, "never auto-seated on a table above the entry bar")
        assertTrue(
            created.room.buyIn <= EntryBar.maxAffordableBuyIn(10_000),
            "created buy-in never exceeds what the caller can fund",
        )
        assertEquals(1_000L, created.room.buyIn)
    }

    @Test
    fun rescue_neverPullsASearcherOntoAnUnaffordableTable() = runTest {
        val svc = service()
        // A lonely 5,000 table. It sits one canonical step above the 1k anchor and
        // is under the searcher's browse ceiling (25k), so the ceiling + one-step
        // rules alone would rescue onto it. But a 4,000-chip searcher can't clear
        // the 4× bar for 5k (needs 20k), so the affordability filter must exclude it.
        val lonely = assertIs<MatchmakingResult.Created>(
            svc.findOrJoinPublic(user(), "Waiter", 5_000, 5_000, noBlocks, callerBalance = 100_000),
        ).room
        val result = svc.findOrJoinPublic(user(), "Fresh", 1_000, 25_000, noBlocks, callerBalance = 4_000)

        val created = assertIs<MatchmakingResult.Created>(result)
        assertTrue(created.room.code != lonely.code, "rescue never seats above the entry bar")
        assertTrue(
            EntryBar.canSit(4_000, created.room.buyIn),
            "the searcher can fund whatever table they land on",
        )
        assertEquals(1_000L, created.room.buyIn)
    }

    @Test
    fun buyInTiers_alignWithRoomSettings_bounds_andDefaultIsCanonical() {
        // Plan §2.3: tiers must line up with RoomSettings. Every canonical tier is a
        // legal buy-in, and the default stake (the convergence anchor) is one of them.
        assertTrue(
            BuyInTier.Canonical.all { it in RoomSettings.MIN_BUY_IN..RoomSettings.MAX_BUY_IN },
            "every canonical tier is a legal buy-in",
        )
        assertTrue(
            RoomSettings.DEFAULT_BUY_IN in BuyInTier.Canonical,
            "the default stake is a canonical tier so overlapping ranges converge on it",
        )
        // forBuyIn must accept each tier without tripping its blind/stack invariants.
        BuyInTier.Canonical.forEach { RoomSettings.forBuyIn(it, maxSeats = RoomService.MAX_SEATS) }
    }

    @Test
    fun lastHumanLeavingABotFilledTable_tearsItDown() = runTest {
        val svc = service()
        val me = user()
        val code = assertIs<MatchmakingResult.Created>(svc.findOrJoinPublic(me, "Me", 5_000, 5_000, noBlocks)).room.code
        // Disclosed bots fill the table (the consent path uses the system host).
        repeat(3) {
            svc.addBot(code, SYSTEM_HOST_USER_ID, BotDifficulty.Standard, revealed = true)
        }
        assertEquals(4, svc.find(code)!!.members.size)

        // The lone human leaves → only bots remain → the table is torn down, not
        // left running hands to nobody.
        svc.leave(code, me)
        assertNull(svc.find(code), "a bot-only table is GC'd when the last human leaves")
    }

    @Test
    fun buyInTier_within_snapsToACanonicalTierInRange_elseClampsTheNearestIntoRange() {
        // Range covering the anchor tier (1,000) picks it — the affordable-default
        // snap, so a fresh 10k grant lands on a table the 4× entry bar clears.
        assertEquals(1_000, BuyInTier.within(1_000, 100_000))
        // Range above the anchor picks the lowest in-range canonical.
        assertEquals(25_000, BuyInTier.within(25_000, 100_000))
        // Range straddling no canonical tier clamps the nearest INTO the range,
        // never outside it — a table created below all tiers must still sit at a
        // buy-in the searcher's own range accepts, or no later searcher with the
        // same range could ever join it (MP-15).
        assertEquals(500, BuyInTier.within(100, 500))
        assertEquals(4_000, BuyInTier.within(3_000, 4_000))
        // The result is always inside the requested range.
        listOf(100L to 500L, 3_000L to 4_000L, 6_000L to 9_000L, 1_000L to 100_000L).forEach { (min, max) ->
            assertTrue(BuyInTier.within(min, max) in min..max, "within($min, $max) lands inside the range")
        }
    }

    @Test
    fun twoSearchers_withAnIdenticalRangeStraddlingNoTier_convergeOnOneTable() = runTest {
        // MP-15 repro: a range that brackets no canonical tier (3,000..4,000)
        // used to mint a table snapped to 5,000 — outside the range — so the
        // second searcher's `buyIn in min..max` filter rejected it and opened a
        // fresh empty table, stranding both. The clamp keeps the created buy-in
        // inside the range so the second searcher seats with the first.
        val svc = service()
        val first = assertIs<MatchmakingResult.Created>(svc.findOrJoinPublic(user(), "Alice", 3_000, 4_000, noBlocks))
        assertTrue(first.room.buyIn in 3_000..4_000, "created table sits inside the searcher's own range")

        val second = svc.findOrJoinPublic(user(), "Bob", 3_000, 4_000, noBlocks)

        val joined = assertIs<MatchmakingResult.Joined>(second)
        assertEquals(first.room.code, joined.room.code, "the second searcher joins the first's table, not a new one")
        assertEquals(2, joined.room.members.size)
    }
}
