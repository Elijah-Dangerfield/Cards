package com.dangerfield.cards.server.routes

import kotlinx.serialization.Serializable

/**
 * Wire format for `GET /v1/products`.
 *
 * Kept separate from the server domain model
 * ([com.dangerfield.cards.server.domain.Product]) so the wire shape can evolve
 * independently and a future Postgres-backed catalog doesn't need to know the
 * JSON envelope. Mapping happens in [ProductsRoutes.toDto].
 *
 * camelCase to match the existing `/v1/app-config` convention. Bumped via
 * [schemaVersion] when we make a breaking change — clients can decline to
 * parse a future version and surface a "please update" prompt.
 *
 * Empty lists are valid: a client should render an empty shop tab rather than
 * an error when there's nothing for sale.
 */
@Serializable
data class ProductCatalogResponse(
    val schemaVersion: Int = 1,
    /**
     * Server's wall clock at the moment this response was generated, in
     * UTC milliseconds. Clients use this PLUS a local monotonic-clock
     * anchor to derive effective server time without trusting their
     * device clock — see [com.dangerfield.cards.libraries.products.CatalogTimeAnchor]
     * on the client side. Required for sale-window countdown UIs that
     * resist clock manipulation.
     */
    val serverNowEpochMs: Long = 0L,
    val chipPacks: List<ChipPackDto> = emptyList(),
    val chipOffers: List<ChipOfferDto> = emptyList(),
)

@Serializable
data class ChipPackDto(
    val id: String,
    val title: String,
    val subtitle: String,
    /** Server-authoritative emoji char rendered as the product's primary
     *  visual. See server domain [com.dangerfield.cards.server.domain.Product.iconEmoji]. */
    val iconEmoji: String,
    val grantsChips: Long,
    val store: StoreSkuDto,
    val featured: Boolean = false,
    val badge: String? = null,
    /**
     * Wall-clock instant (UTC ms) after which this offer disappears from
     * the shop. Null = no expiry. Compare against [ProductCatalogResponse.serverNowEpochMs]
     * for a clock-spoof-resistant countdown.
     */
    val availableUntilEpochMs: Long? = null,
    /**
     * Always `false` for chip packs (they're consumed on purchase, not
     * equipped). Declared on the wire for symmetry with [ChipOfferDto]
     * so the client doesn't have to special-case the kind to know.
     */
    val isEquippable: Boolean = false,
)

@Serializable
data class ChipOfferDto(
    val id: String,
    val title: String,
    val subtitle: String,
    /** See [ChipPackDto.iconEmoji]. */
    val iconEmoji: String,
    val costChips: Long,
    val grantsKey: String,
    val featured: Boolean = false,
    val badge: String? = null,
    /**
     * Long-form description shown in the purchase-confirmation sheet so the
     * user knows what they're buying ("Send this emote when you win — it
     * fills everyone's screen.") Optional; UI falls back to [subtitle] when
     * the server doesn't provide one.
     */
    val description: String? = null,
    /**
     * Minimum player level required to purchase. 1 / null = no level gate.
     * See server domain `Product.ChipOffer.unlockLevel`.
     */
    val unlockLevel: Int? = null,
    /** See [ChipPackDto.availableUntilEpochMs]. */
    val availableUntilEpochMs: Long? = null,
    /**
     * `true` when this product is a visual cosmetic the user actively
     * equips (felts, card backs, titles, table themes, tools). `false`
     * for unlock-style packs (avatar packs, emote packs). The client
     * uses this to gate the Equip/Unequip button on the My Items row;
     * unlocks are owned but never "equipped" — they just expand the
     * relevant picker.
     */
    val isEquippable: Boolean = false,
)

/**
 * Single store SKU for the requesting client's platform. The server has both
 * iOS and Android SKUs internally but only surfaces the relevant one — the
 * other is dead weight on the wire.
 */
@Serializable
data class StoreSkuDto(
    val sku: String,
    /** Display price to show while the platform store fetch is in-flight. */
    val fallbackPriceDisplay: String,
)
