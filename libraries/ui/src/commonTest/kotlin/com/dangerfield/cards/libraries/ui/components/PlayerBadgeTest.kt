package com.dangerfield.cards.libraries.ui.components

import com.dangerfield.cards.libraries.cards.AcquisitionSource
import com.dangerfield.cards.libraries.cards.InventoryItem
import com.dangerfield.cards.libraries.cards.PurchaseState
import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.products.ProductCatalog
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [resolvePlayerBadges] — the seam that turns a player's equipped product
 * ids into the badge chips on their player card. Badges + titles are unified
 * (both resolve), only Badge-/Title-slot products are kept, display metadata
 * comes from the catalog (incl. the prestige bucket), and earned-at is folded in
 * from inventory when present (self) and absent otherwise (opponents).
 */
class PlayerBadgeTest {

    private fun prestigeOffer(id: String, title: String, emoji: String, description: String) =
        Product.ChipOffer(
            id = id,
            title = title,
            subtitle = "",
            iconEmoji = emoji,
            costChips = 0,
            grantsKey = id,
            description = description,
        )

    private val catalog = ProductCatalog(
        chipOffers = listOf(
            prestigeOffer("felt_royal_red", "Royal Red", "🟥", "A felt"),
        ),
        prestige = listOf(
            prestigeOffer("badge_founding_member_1000", "Founding Member", "🏛", "First 1,000 players."),
            prestigeOffer("title_pot_magnet", "Pot Magnet", "🧲", "Sit at a 5,000-chip pot."),
        ),
    )

    @Test
    fun resolves_badgeAndTitle_fromCatalog_unified() {
        val badges = resolvePlayerBadges(
            equippedProductIds = listOf("badge_founding_member_1000", "title_pot_magnet"),
            catalog = catalog,
        )

        assertEquals(2, badges.size)
        assertEquals(
            PlayerBadge("badge_founding_member_1000", "🏛", "Founding Member", "First 1,000 players.", null),
            badges[0],
        )
        assertEquals("Pot Magnet", badges[1].name)
        assertEquals("🧲", badges[1].emoji)
    }

    @Test
    fun dropsNonBadgeSlots_and_unknownIds() {
        val badges = resolvePlayerBadges(
            // A felt (not a badge/title slot) and an id the catalog doesn't know.
            equippedProductIds = listOf("felt_royal_red", "title_unknown_xyz", "title_pot_magnet"),
            catalog = catalog,
        )

        assertEquals(listOf("title_pot_magnet"), badges.map { it.productId })
    }

    @Test
    fun foldsEarnedAt_fromInventory_whenPresent() {
        val inventory = listOf(
            InventoryItem(
                productId = "title_pot_magnet",
                state = PurchaseState.Confirmed,
                purchasedAtEpochMs = 1_700_000_000_000,
                costChipsAtPurchase = 0,
                acquisitionSource = AcquisitionSource.Earned,
            ),
        )

        val badges = resolvePlayerBadges(
            equippedProductIds = listOf("title_pot_magnet"),
            catalog = catalog,
            inventory = inventory,
        )

        assertEquals(1_700_000_000_000, badges.single().earnedAtEpochMs)
    }
}
