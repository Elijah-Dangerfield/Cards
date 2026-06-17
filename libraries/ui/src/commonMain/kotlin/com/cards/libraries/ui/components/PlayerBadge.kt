package com.dangerfield.cards.libraries.ui.components

import com.dangerfield.cards.libraries.cards.CosmeticSlot
import com.dangerfield.cards.libraries.cards.InventoryItem
import com.dangerfield.cards.libraries.cards.cosmeticSlotFor
import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.products.ProductCatalog

/**
 * A badge on a Player Card — the unified view of the prestige cosmetics a player
 * shows off. Founding-member-style **badges** and player **titles** are no
 * longer distinguished: both render as a tappable chip (emoji + name) you can
 * open to read about. Name / emoji / description come from the product catalog
 * (the backend) rather than a hardcoded client map.
 *
 * @param earnedAtEpochMs when the player acquired it, or null if unknown (e.g. a
 *   remote opponent's badge before their grant time is plumbed through).
 */
data class PlayerBadge(
    val productId: String,
    val emoji: String,
    val name: String,
    val description: String? = null,
    val earnedAtEpochMs: Long? = null,
)

/**
 * Resolve the [PlayerBadge]s a player is showing — their equipped Badge- and
 * Title-slot cosmetics — from [equippedProductIds], joining display metadata
 * from [catalog] (which now includes the unlock-only prestige bucket) and the
 * acquired-at time from [inventory] when available. Ids the catalog doesn't know
 * yet are dropped rather than rendered as a bare glyph.
 */
fun resolvePlayerBadges(
    equippedProductIds: Collection<String>,
    catalog: ProductCatalog,
    inventory: List<InventoryItem> = emptyList(),
): List<PlayerBadge> {
    val acquiredById = inventory.associate { it.productId to it.purchasedAtEpochMs }
    return equippedProductIds
        .filter {
            val slot = cosmeticSlotFor(it)
            slot == CosmeticSlot.Badge || slot == CosmeticSlot.Title
        }
        .mapNotNull { id ->
            val product = catalog.findById(id) ?: return@mapNotNull null
            PlayerBadge(
                productId = id,
                emoji = product.iconEmoji,
                name = product.title,
                description = (product as? Product.ChipOffer)?.description,
                earnedAtEpochMs = acquiredById[id]?.takeIf { it > 0L },
            )
        }
}
