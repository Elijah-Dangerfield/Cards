package com.dangerfield.cards.libraries.achievements

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The achievement-counter fold is the single source of truth for "how a hand
 * moves the numbers," run identically on client and server — so it's pinned here
 * by counter *shape* (accumulator, high-water, streak, armed-flag, per-key) plus
 * the order-dependence that makes server-side derivation correct.
 */
class AchievementCountersTest {

    private fun hand(
        idempotencyKey: String = "k",
        won: Boolean = false,
        folded: Boolean = false,
        lostAtShowdown: Boolean = false,
        vsBot: Boolean = false,
        beatenBotId: String? = null,
        botDifficulty: String? = null,
        busted: Boolean = false,
        startStack: Long = 0,
        endStack: Long = 0,
        bigBlind: Long = 0,
        potTotal: Long = 0,
        wasAllIn: Boolean = false,
        wonByFold: Boolean = false,
        bustsDealt: Int = 0,
        foldedWouldHaveLost: Boolean = false,
        handStrengthShown: String? = null,
    ) = HandFacts(
        idempotencyKey, HandFacts.MODE_BOTS, won, folded, lostAtShowdown, vsBot, beatenBotId,
        botDifficulty, busted, startStack, endStack, bigBlind, potTotal, wasAllIn, wonByFold,
        bustsDealt, foldedWouldHaveLost, handStrengthShown,
    )

    private fun foldAll(vararg hands: HandFacts): AchievementCounters =
        hands.fold(AchievementCounters.EMPTY) { acc, h -> acc.fold(h) }

    @Test
    fun accumulators_countQualifyingHands() {
        val c = foldAll(
            hand(won = true, vsBot = true),
            hand(folded = true),
            hand(lostAtShowdown = true),
        )
        assertEquals(3, c[AchievementCounters.HANDS_PLAYED])
        assertEquals(1, c[AchievementCounters.HANDS_WON])
        assertEquals(1, c[AchievementCounters.HANDS_FOLDED])
        assertEquals(1, c[AchievementCounters.HANDS_LOST_AT_SHOWDOWN])
        assertEquals(1, c[AchievementCounters.BOT_HANDS_PLAYED])
    }

    @Test
    fun noBustStreak_resetsOnBust_andTracksBest() {
        val c = foldAll(
            hand(), hand(), hand(),       // streak 3
            hand(busted = true),          // reset to 0
            hand(), hand(),               // streak 2
        )
        assertEquals(2, c[AchievementCounters.NO_BUST_STREAK], "current streak after the last two")
        assertEquals(3, c[AchievementCounters.BEST_NO_BUST_STREAK], "best is the high-water before the bust")
    }

    @Test
    fun maxPot_isHighWaterMark_notSum() {
        val c = foldAll(
            hand(potTotal = 400, bigBlind = 20),
            hand(potTotal = 1_000, bigBlind = 20),
            hand(potTotal = 300, bigBlind = 20),
        )
        assertEquals(1_000, c[AchievementCounters.MAX_POT_SEEN])
        assertEquals(50, c[AchievementCounters.MAX_POT_BB_RATIO], "1000 / 20 BB")
    }

    @Test
    fun perBotWins_accumulatePerKey_onlyOnWinVsThatBot() {
        val c = foldAll(
            hand(won = true, vsBot = true, beatenBotId = "Jane"),
            hand(won = true, vsBot = true, beatenBotId = "Jane"),
            hand(won = true, vsBot = true, beatenBotId = "Mike"),
            hand(won = false, vsBot = true, beatenBotId = "Jane"), // a loss doesn't count
        )
        assertEquals(2, c[AchievementCounters.winsVsBot("Jane")])
        assertEquals(1, c[AchievementCounters.winsVsBot("Mike")])
    }

    @Test
    fun dontCallItComeback_armsUnderTenBb_firesOverHundredBb_once() {
        val bb = 50L
        val c = foldAll(
            hand(endStack = 8 * bb, bigBlind = bb),    // arm (≤ 10 BB)
            hand(endStack = 40 * bb, bigBlind = bb),   // still climbing, no fire
            hand(endStack = 120 * bb, bigBlind = bb),  // fire (≥ 100 BB), disarm
            hand(endStack = 130 * bb, bigBlind = bb),  // already disarmed — no double-fire
        )
        assertEquals(1, c[AchievementCounters.DONT_CALL_IT_COMEBACK])
        assertEquals(0, c[AchievementCounters.SHORT_STACK_ARMED], "latch reset after firing")
    }

    @Test
    fun stackSwings_doubleAndTriple() {
        val c = foldAll(
            hand(startStack = 1_000, endStack = 2_000), // double
            hand(startStack = 1_000, endStack = 3_500), // double + triple
        )
        assertEquals(2, c[AchievementCounters.DOUBLED_UP])
        assertEquals(1, c[AchievementCounters.TRIPLED_UP])
    }

    @Test
    fun comeback5bb_needsShortStartAndDouble() {
        val bb = 100L
        val c = foldAll(
            hand(startStack = 4 * bb, endStack = 9 * bb, bigBlind = bb),   // ≤5BB start, doubled → counts
            hand(startStack = 50 * bb, endStack = 200 * bb, bigBlind = bb), // not short → no
        )
        assertEquals(1, c[AchievementCounters.COMEBACK_5BB])
    }

    @Test
    fun decisiveActions_winByFold_goodFold_allIn_bustsDealt() {
        val c = foldAll(
            hand(wonByFold = true),
            hand(folded = true, foldedWouldHaveLost = true),
            hand(wasAllIn = true),
            hand(won = true, bustsDealt = 2),
        )
        assertEquals(1, c[AchievementCounters.WIN_BY_FOLD])
        assertEquals(1, c[AchievementCounters.GOOD_FOLD])
        assertEquals(1, c[AchievementCounters.ALL_IN_HANDS])
        assertEquals(2, c[AchievementCounters.BUSTS_DEALT])
    }

    @Test
    fun challengingWins_onlyCountOnChallengingDifficulty() {
        val c = foldAll(
            hand(won = true, vsBot = true, botDifficulty = "Challenging"),
            hand(won = true, vsBot = true, botDifficulty = "Casual"),
        )
        assertEquals(1, c[AchievementCounters.CHALLENGING_WINS])
    }

    @Test
    fun handStrengthShown_countsCategoryAndAllWeakerOnes() {
        // Showing a flush counts toward "show at least a pair / two pair / ... / flush".
        val c = foldAll(hand(handStrengthShown = ShownHand.Flush.name))
        assertEquals(1, c[AchievementCounters.showKey(ShownHand.Pair)])
        assertEquals(1, c[AchievementCounters.showKey(ShownHand.Straight)])
        assertEquals(1, c[AchievementCounters.showKey(ShownHand.Flush)])
        assertEquals(0, c[AchievementCounters.showKey(ShownHand.FullHouse)], "nothing stronger than shown")
    }

    @Test
    fun fold_isPure_doesNotMutateReceiver() {
        val base = AchievementCounters.EMPTY.fold(hand(won = true))
        val snapshot = base.values.toMap()
        base.fold(hand(won = true)) // produces a new value
        assertEquals(snapshot, base.values, "fold returns a new snapshot, never mutates in place")
    }
}
