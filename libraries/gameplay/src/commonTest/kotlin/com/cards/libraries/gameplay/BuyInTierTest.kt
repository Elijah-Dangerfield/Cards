package com.dangerfield.cards.libraries.gameplay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [BuyInTier]'s snap behaviour now that it's the shared source of truth for
 * both the matchmaker's table creation and the Find screen's "what you'll get"
 * preview. The load-bearing case is the fresh-player default band: it must snap
 * to the affordable [BuyInTier.ANCHOR] tier, not a higher one the 4× entry bar
 * would bounce.
 */
class BuyInTierTest {

    @Test
    fun within_defaultBand_snapsToTheAffordableAnchor() {
        assertEquals(1_000, BuyInTier.within(500, 2_000), "the default band snaps to the 1,000 anchor")
        assertEquals(BuyInTier.ANCHOR, BuyInTier.within(500, 2_000))
    }

    @Test
    fun within_prefersTheInRangeTierClosestToTheAnchor() {
        // 1,000 and 5,000 both fall in range; the anchor tiebreak takes 1,000.
        assertEquals(1_000, BuyInTier.within(1_000, 5_000))
        // Only 5,000 is in range here.
        assertEquals(5_000, BuyInTier.within(4_000, 6_000))
    }

    @Test
    fun within_isAlwaysInsideTheRange_evenWhenNoTierFits() {
        val snapped = BuyInTier.within(1_500, 1_900)
        assertTrue(snapped in 1_500..1_900, "a range straddling no tier still snaps inside it")
    }

    @Test
    fun withinOneStep_mergesAdjacentStakes_notDistantOnes() {
        assertTrue(BuyInTier.withinOneStep(1_000, 5_000), "one canonical step apart merges")
        assertTrue(!BuyInTier.withinOneStep(1_000, 25_000), "two steps apart is too far")
    }
}
