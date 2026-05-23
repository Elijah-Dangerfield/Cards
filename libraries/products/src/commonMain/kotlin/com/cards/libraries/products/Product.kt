package com.dangerfield.cards.libraries.products

import kotlinx.serialization.Serializable

/**
 * Client-side domain model for a shop product.
 *
 * Two flavors, distinguished by how the user pays:
 *  - [ChipPack] — purchased with real money via the platform store (IAP).
 *  - [ChipOffer] — purchased with in-game chips.
 *
 * Display strings are pre-localized by the server using the request's
 * `Accept-Language` header — the client never sees raw locale maps. UI just
 * renders [title] / [subtitle] / [badge] / [description] as-is.
 *
 * Primary visual: [iconEmoji]. The server is authoritative for what a
 * product looks like; the client never maps from a key to a visual. When
 * real drawable assets ship, a sibling field will be added at that point;
 * the emoji stays as the universal fallback so clients without the asset
 * bundle still render.
 *
 * For [ChipPack.store.fallbackPriceDisplay]: placeholder price the UI
 * shows while the platform store fetch is in-flight or fails. The
 * platform store is the source of truth for the localized price the user
 * actually pays — the SKU is the join key.
 *
 * `@Serializable` so the repo can cache a snapshot to disk later if we
 * want offline reads. Currently the cache lives in memory only.
 */
@Serializable
sealed interface Product {
    val id: String
    val title: String
    val subtitle: String
    val iconEmoji: String
    val featured: Boolean
    val badge: String?

    /**
     * Wall-clock instant (UTC ms) after which this product disappears
     * from the shop. Null = always available. Once owned, ownership is
     * permanent regardless of this field — only the offer's *purchase
     * window* is time-limited.
     *
     * Critical: countdown UIs must NOT compare this against
     * `Clock.System.now()` on the device — that trusts the device
     * clock. Use [com.dangerfield.cards.libraries.products.CatalogTimeAnchor]
     * which is anchored to the server's clock at fetch time and
     * advances via the device's monotonic clock (resistant to wall-
     * clock manipulation).
     */
    val availableUntilEpochMs: Long?

    /**
     * Server-authoritative flag — `true` when this product is a visual
     * cosmetic the user actively equips (felts, card backs, titles,
     * table themes, tools). `false` for unlock-style products: chip
     * packs (consumed on purchase) and avatar / emote packs (owning
     * the pack adds options to a picker — there's nothing to equip).
     *
     * Used to gate the Equip/Unequip button on the My Items row. Slot
     * routing (one felt at a time, one card back at a time) still
     * lives in `CosmeticCategory.kt` by product-id prefix; the
     * `isEquippable` field replaced the prefix logic for the
     * "does this item have an equip button at all" question.
     */
    val isEquippable: Boolean

    @Serializable
    data class ChipPack(
        override val id: String,
        override val title: String,
        override val subtitle: String,
        override val iconEmoji: String,
        val grantsChips: Long,
        val store: StoreSku,
        override val featured: Boolean = false,
        override val badge: String? = null,
        override val availableUntilEpochMs: Long? = null,
        // Chip packs are consumable — grant chips on purchase, never
        // equipped. Always false; declared so the field is visible
        // in pattern-matched callsites without having to special-case
        // the subtype.
        override val isEquippable: Boolean = false,
    ) : Product

    @Serializable
    data class ChipOffer(
        override val id: String,
        override val title: String,
        override val subtitle: String,
        override val iconEmoji: String,
        val costChips: Long,
        val grantsKey: String,
        override val featured: Boolean = false,
        override val badge: String? = null,
        override val availableUntilEpochMs: Long? = null,
        /**
         * Minimum player level required to purchase. 1 / null = no gate.
         * Locked products are still shown in the shop (as a "carrot") but
         * rendered with a lock overlay + "Unlocks at Level N" footer.
         */
        val unlockLevel: Int? = null,
        /**
         * Long-form explanation of what the user actually gets. Where
         * [subtitle] is a category label ("Emote", "Card back"), this is
         * one or two sentences explaining the behavior. Server-localized.
         *
         * Null = no description provided; UI should fall back to
         * [subtitle] rather than rendering an empty block.
         */
        val description: String? = null,
        // Server-driven. Visual cosmetics → true; unlock-style packs
        // (avatar / emote packs) → false. See sealed-parent KDoc.
        override val isEquippable: Boolean = false,
    ) : Product
}

/** Platform store join key for [Product.ChipPack]. */
@Serializable
data class StoreSku(
    val sku: String,
    val fallbackPriceDisplay: String,
)

/** Full shop catalog snapshot. */
@Serializable
data class ProductCatalog(
    val chipPacks: List<Product.ChipPack> = emptyList(),
    val chipOffers: List<Product.ChipOffer> = emptyList(),
) {
    val isEmpty: Boolean
        get() = chipPacks.isEmpty() && chipOffers.isEmpty()

    companion object {
        val Empty: ProductCatalog = ProductCatalog()
    }
}
