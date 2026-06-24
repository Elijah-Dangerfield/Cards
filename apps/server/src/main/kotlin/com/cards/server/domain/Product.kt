package com.dangerfield.cards.server.domain

/**
 * Server-side domain model for a shop product. Wire format lives separately in
 * `routes/ProductsDto.kt` — keeping these apart means the wire shape can
 * evolve without touching catalog sources, and a future Postgres-backed source
 * doesn't need to know the JSON envelope.
 *
 * A "product" is anything the user can acquire from the shop, regardless of
 * how they pay for it:
 *  - [ChipPack] — purchased with real money via the platform store (IAP).
 *  - [ChipOffer] — purchased with in-game chips. (V1 placeholder; no items in
 *    the catalog yet, but the type exists so the UI and the wire format are
 *    ready when we add cosmetics / table themes.)
 *
 * Localized strings are stored as a map of `BCP 47 tag -> string` so the
 * endpoint can resolve them against the request's `Accept-Language` priority
 * list. English is required as a fallback.
 */
sealed interface Product {
    val id: String

    /** Maps BCP 47 language tag (e.g. "en", "en-US", "es") → display string. */
    val titleByLocale: Map<String, String>
    val subtitleByLocale: Map<String, String>

    /**
     * Single emoji rendered as the product's primary visual everywhere
     * (grid tile, sheet bubble, etc). Server-authoritative — no client-
     * side iconKey → emoji guessing.
     *
     * When real drawable assets ship, a sibling field (e.g. `iconUrl`
     * or `assetId`) will be added; this emoji stays as the universal
     * fallback so clients without the asset bundle still render.
     */
    val iconEmoji: String

    val featured: Boolean
    /** Free-form short marker like "BEST VALUE" or "+20%". Null = no badge. */
    val badgeByLocale: Map<String, String>?

    /**
     * Wall-clock instant (UTC ms since epoch) after which this product
     * stops being **purchasable**. Null = always purchasable.
     *
     * Once a player owns the product, ownership is permanent regardless
     * of this field — only the *offer* expires, not the item.
     *
     * The route uses this to filter the catalog response by the server's
     * own clock, so device clock manipulation can't make an expired offer
     * visible. Authoritative purchase-time validation should also re-
     * check this on the server side.
     */
    val availableUntilEpochMs: Long?

    /** Platforms this product is sold on. */
    val platforms: Set<com.dangerfield.cards.server.http.ClientContext.Platform>

    /**
     * `true` when this product is a visual cosmetic the user actively
     * equips (felts, card backs, titles, tools, table themes). `false` for
     * unlocks — chip packs (consumed on purchase), avatar packs and emote
     * packs (owning the pack adds options to a picker, no equip toggle).
     *
     * The client uses this to decide whether to render an Equip/Unequip
     * button on the My Items row. Slot-collision routing (one felt at a
     * time, one card back at a time) still lives in client code today —
     * promoting that to the product row is a separate slice.
     */
    val isEquippable: Boolean

    data class ChipPack(
        override val id: String,
        override val titleByLocale: Map<String, String>,
        override val subtitleByLocale: Map<String, String>,
        override val iconEmoji: String,
        override val featured: Boolean = false,
        override val badgeByLocale: Map<String, String>? = null,
        override val availableUntilEpochMs: Long? = null,
        override val platforms: Set<com.dangerfield.cards.server.http.ClientContext.Platform> =
            setOf(
                com.dangerfield.cards.server.http.ClientContext.Platform.Android,
                com.dangerfield.cards.server.http.ClientContext.Platform.iOS,
                // Include `Other` in the default set so debug clients (curl,
                // web preview) and unknown platforms see the full catalog
                // rather than an empty list. Real platform-exclusive products
                // override this to narrow the set.
                com.dangerfield.cards.server.http.ClientContext.Platform.Other,
            ),
        // Chip packs are consumable — they grant chips on purchase and
        // never appear as something to "equip." Always false.
        override val isEquippable: Boolean = false,
        val grantsChips: Long,
        val store: PlatformStore,
    ) : Product

    data class ChipOffer(
        override val id: String,
        override val titleByLocale: Map<String, String>,
        override val subtitleByLocale: Map<String, String>,
        override val iconEmoji: String,
        override val featured: Boolean = false,
        override val badgeByLocale: Map<String, String>? = null,
        override val availableUntilEpochMs: Long? = null,
        override val platforms: Set<com.dangerfield.cards.server.http.ClientContext.Platform> =
            setOf(
                com.dangerfield.cards.server.http.ClientContext.Platform.Android,
                com.dangerfield.cards.server.http.ClientContext.Platform.iOS,
                // Include `Other` in the default set so debug clients (curl,
                // web preview) and unknown platforms see the full catalog
                // rather than an empty list. Real platform-exclusive products
                // override this to narrow the set.
                com.dangerfield.cards.server.http.ClientContext.Platform.Other,
            ),
        val costChips: Long,
        /** What buying this unlocks — opaque key the client maps to inventory. */
        val grantsKey: String,
        /**
         * Minimum player level required to purchase. Clients with a lower
         * level should still see the product in the shop (so it's a
         * visible carrot), but render it as locked with the unlock
         * requirement called out.
         *
         * 1 (or null) = no level gate — available at signup.
         *
         * Locked offers usually still have a [costChips] cost the user
         * must pay on top of the level requirement (level unlocks the
         * RIGHT to buy, not the item itself).
         */
        val unlockLevel: Int? = null,
        /**
         * Longer-form explanation of what the user actually gets. The
         * tile-level [subtitleByLocale] is a category label ("Emote",
         * "Card back") — this is the sentence-or-two that tells a new
         * player what "Victory Dance" or "Royal Red Felt" actually does in a
         * hand. Surfaced in the purchase confirmation sheet.
         *
         * Optional — items without one fall back to the subtitle in UI.
         * BCP-47 tag → string, same as the other localized fields.
         */
        val descriptionByLocale: Map<String, String>? = null,
        // Surfaced from the products row. True for visual cosmetics
        // (felts, card backs, titles, table themes, tools); false for
        // unlock-style packs (avatar packs, emote packs) and any future
        // category whose value is "owning it" rather than "displaying it."
        override val isEquippable: Boolean = false,
    ) : Product
}

/**
 * Per-platform store metadata for an IAP product.
 *
 * The server returns BOTH platform SKUs in the response, scoped to the
 * requesting client's platform via the endpoint — the client only sees its
 * own. `fallbackPriceDisplay` is the price string to show while the platform
 * store fetch is in-flight (or if it fails); the platform store's localized
 * price is always authoritative once available.
 */
data class PlatformStore(
    val ios: StoreSku,
    val android: StoreSku,
) {
    data class StoreSku(
        val sku: String,
        val fallbackPriceDisplay: String,
    )
}
