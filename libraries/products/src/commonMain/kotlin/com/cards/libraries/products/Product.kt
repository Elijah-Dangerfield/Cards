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
 * renders [title] / [subtitle] / [badge] as-is.
 *
 * For [ChipPack.store.fallbackPriceDisplay]: this is the placeholder price
 * the UI shows while the platform store fetch is in-flight or fails. The
 * platform store (App Store / Play Store) is always the source of truth for
 * the actual localized price the user pays — the SKU is the join key.
 *
 * `@Serializable` so the repo can cache a snapshot to disk later if we want
 * offline reads. Currently the cache lives in memory only.
 */
@Serializable
sealed interface Product {
    val id: String
    val title: String
    val subtitle: String
    val iconKey: String
    val featured: Boolean
    val badge: String?

    @Serializable
    data class ChipPack(
        override val id: String,
        override val title: String,
        override val subtitle: String,
        override val iconKey: String,
        /**
         * Server-authoritative emoji char the UI renders as the product's
         * primary visual. Preferred over deriving a placeholder from
         * [iconKey] client-side. Nullable for forward compatibility with
         * older servers that haven't started shipping it yet — client
         * falls back to an iconKey-based mapping when null.
         */
        val iconEmoji: String? = null,
        val grantsChips: Long,
        val store: StoreSku,
        override val featured: Boolean = false,
        override val badge: String? = null,
    ) : Product

    @Serializable
    data class ChipOffer(
        override val id: String,
        override val title: String,
        override val subtitle: String,
        override val iconKey: String,
        /** See [ChipPack.iconEmoji]. */
        val iconEmoji: String? = null,
        val costChips: Long,
        val grantsKey: String,
        override val featured: Boolean = false,
        override val badge: String? = null,
        /**
         * Minimum player level required to purchase. 1 / null = no gate.
         * Locked products are still shown in the shop (as a "carrot") but
         * rendered with a lock overlay + "Unlocks at Level N" footer.
         */
        val unlockLevel: Int? = null,
        /**
         * Long-form explanation of what the user actually gets. Where
         * [subtitle] is a category label ("Emote", "Card back"), this is
         * one or two sentences explaining the behavior ("Send a celebration
         * dance to the table when you win a hand…"). Server-localized.
         *
         * Null = no description provided; UI should fall back to [subtitle]
         * rather than rendering an empty block.
         */
        val description: String? = null,
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
