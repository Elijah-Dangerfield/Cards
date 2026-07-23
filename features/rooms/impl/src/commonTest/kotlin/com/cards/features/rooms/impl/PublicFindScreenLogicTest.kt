package com.dangerfield.cards.features.rooms.impl

import com.dangerfield.cards.libraries.gameplay.BuyInTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun lowStakesBand_occupiesReadableTrackSpace_atTheTableCeiling() {
        // ROOM-21: on the old linear 100..100k scale the whole default band sat in
        // the leftmost ~2% of the track and read as "zero to 500". The log scale
        // must give the playable low tiers real room — the 2,000 thumb lands past
        // a quarter of the track, and the 500/2,000 thumbs are visibly apart.
        val max = 100_000
        val at500 = fractionForBuyIn(500, max)
        val at2000 = fractionForBuyIn(2_000, max)
        assertTrue(at2000 >= 0.25f, "2,000 chips should sit past 1/4 of the track, was $at2000")
        assertTrue(at2000 - at500 >= 0.1f, "the default band should be visibly wide, was ${at2000 - at500}")
    }

    @Test
    fun chipSelection_roundTrips_atAnyScale() {
        // The selection is stored in chips; the slider re-derives thumb positions
        // whenever the wallet ceiling changes. A chosen figure must survive the
        // fraction round-trip at every scale it can be shown on.
        for (max in listOf(2_000, 9_300, 10_000, 50_000, 100_000)) {
            for (chips in listOf(100, 500, 1_000, 2_000).filter { it <= max }) {
                assertEquals(
                    chips,
                    buyInFor(fractionForBuyIn(chips, max), max),
                    "chips=$chips should survive the round-trip at scale max=$max",
                )
            }
        }
    }

    @Test
    fun bandClamp_keepsTheSelectionInsideTheAffordableScale() {
        // Wallet caps below the chosen top: the top clamps, the floor survives.
        assertEquals(500..1_500, clampBandToScale(500..2_000, maxBuyIn = 1_500))
        // Wallet caps below the whole band: both ends land on the ceiling.
        assertEquals(2_000..2_000, clampBandToScale(5_000..20_000, maxBuyIn = 2_000))
        // Nothing to clamp: the band passes through untouched.
        assertEquals(500..2_000, clampBandToScale(500..2_000, maxBuyIn = 100_000))
    }
}
