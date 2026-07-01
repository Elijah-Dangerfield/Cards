package com.dangerfield.cards.features.profile.impl.items

import com.dangerfield.cards.libraries.cards.AcquisitionSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the acquisition-line decision (SHOP-4): default/starter cosmetics are
 * granted at account creation, so they must never show an "Earned"/"Bought"
 * line even though their seeded `acquiredAtEpochMs` resolves to "today".
 */
class AcquisitionLineTest {

    private fun ownedItem(
        productId: String,
        acquisitionSource: AcquisitionSource = AcquisitionSource.Purchased,
        acquiredAtEpochMs: Long = NOW,
        costChipsAtPurchase: Long = 0L,
    ) = OwnedItem(
        productId = productId,
        title = "Any",
        subtitle = "",
        description = null,
        iconEmoji = "🂠",
        isEquipped = true,
        isEquippable = true,
        acquisitionSource = acquisitionSource,
        acquiredAtEpochMs = acquiredAtEpochMs,
        costChipsAtPurchase = costChipsAtPurchase,
    )

    @Test
    fun defaultCardBack_showsNoLine() {
        assertNull(acquisitionLineKind(ownedItem("cardback_default")))
    }

    @Test
    fun defaultFelt_showsNoLine() {
        assertNull(acquisitionLineKind(ownedItem("felt_default")))
    }

    @Test
    fun earnedCosmetic_showsEarnedLine() {
        val kind = acquisitionLineKind(
            ownedItem("cardback_champion", acquisitionSource = AcquisitionSource.Earned),
        )
        assertEquals(AcquisitionLineKind.Earned, kind)
    }

    @Test
    fun boughtCosmetic_showsBoughtLineWithCost() {
        val kind = acquisitionLineKind(
            ownedItem("felt_royal_red", costChipsAtPurchase = 1_500L),
        )
        assertEquals(AcquisitionLineKind.Bought(1_500L), kind)
    }

    @Test
    fun freeGrant_showsBoughtFreeLine() {
        val kind = acquisitionLineKind(ownedItem("cardback_neon"))
        assertEquals(AcquisitionLineKind.BoughtFree, kind)
    }

    @Test
    fun noAcquisitionTimestamp_showsNoLine() {
        assertNull(acquisitionLineKind(ownedItem("cardback_neon", acquiredAtEpochMs = 0L)))
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
