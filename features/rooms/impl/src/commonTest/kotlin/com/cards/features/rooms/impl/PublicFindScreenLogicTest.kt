package com.dangerfield.cards.features.rooms.impl

import com.dangerfield.cards.libraries.gameplay.BuyInTier
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the buy-in slider math the Find screen relies on. The load-bearing case is
 * the fixed-chip default band: whatever the wallet ceiling, the thumbs must resolve
 * to the real 500 / 2,000 chip figures (not a fraction of balance), and that band
 * must snap to the affordable 1,000 tier the matchmaker will actually seat at.
 */
class PublicFindScreenLogicTest {

    @Test
    fun defaultThumbs_resolveTo500And2000_atTheStarterGrantScale() {
        // A fresh player's 10,000 grant is the slider ceiling.
        val max = 10_000
        assertEquals(DEFAULT_BAND_MIN, buyInFor(fractionForBuyIn(DEFAULT_BAND_MIN, max), max))
        assertEquals(DEFAULT_BAND_MAX, buyInFor(fractionForBuyIn(DEFAULT_BAND_MAX, max), max))
    }

    @Test
    fun defaultThumbs_resolveTo500And2000_regardlessOfCeiling() {
        // The band is fixed chips, so a much larger wallet still lands the thumbs on
        // the same 500 / 2,000 figures.
        val max = 100_000
        assertEquals(DEFAULT_BAND_MIN, buyInFor(fractionForBuyIn(DEFAULT_BAND_MIN, max), max))
        assertEquals(DEFAULT_BAND_MAX, buyInFor(fractionForBuyIn(DEFAULT_BAND_MAX, max), max))
    }

    @Test
    fun defaultBand_snapsToTheAffordableAnchorTier() {
        val max = 10_000
        val min = buyInFor(fractionForBuyIn(DEFAULT_BAND_MIN, max), max).toLong()
        val top = buyInFor(fractionForBuyIn(DEFAULT_BAND_MAX, max), max).toLong()
        assertEquals(1_000, BuyInTier.within(min, top), "the default band seats at the 1,000 tier")
    }
}
