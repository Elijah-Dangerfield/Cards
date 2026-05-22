package com.dangerfield.cards.libraries.gameplay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StakeTierTest {

    @Test
    fun allTiers_satisfyRoomSettingsConstraints() {
        StakeTier.entries.forEach { tier ->
            tier.toRoomSettings()
        }
    }

    @Test
    fun allTiers_haveBuyInOf100xBigBlind() {
        StakeTier.entries.forEach { tier ->
            assertEquals(
                tier.bigBlind * 100, tier.buyIn,
                "${tier.name}: buy-in must be 100× big blind per spec §5.3",
            )
        }
    }

    @Test
    fun allTiers_haveBigBlindOfTwiceSmallBlind() {
        StakeTier.entries.forEach { tier ->
            assertEquals(
                tier.smallBlind * 2, tier.bigBlind,
                "${tier.name}: big blind must be 2× small blind",
            )
        }
    }

    @Test
    fun tiers_areOrderedFromCheapestToMostExpensive() {
        val tiers = StakeTier.entries
        for (i in 1 until tiers.size) {
            assertTrue(
                tiers[i].buyIn > tiers[i - 1].buyIn,
                "${tiers[i].name} buy-in (${tiers[i].buyIn}) must exceed ${tiers[i - 1].name} buy-in (${tiers[i - 1].buyIn})",
            )
        }
    }

    @Test
    fun fromName_unknownFallsBackToDefault() {
        assertEquals(StakeTier.Default, StakeTier.fromName(null))
        assertEquals(StakeTier.Default, StakeTier.fromName(""))
        assertEquals(StakeTier.Default, StakeTier.fromName("Whale"))
    }

    @Test
    fun fromName_roundTripsValidNames() {
        StakeTier.entries.forEach { tier ->
            assertEquals(tier, StakeTier.fromName(tier.name))
        }
    }
}
