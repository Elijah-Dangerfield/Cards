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
    val chipPacks: List<ChipPackDto> = emptyList(),
    val chipOffers: List<ChipOfferDto> = emptyList(),
)

@Serializable
data class ChipPackDto(
    val id: String,
    val title: String,
    val subtitle: String,
    val iconKey: String,
    val grantsChips: Long,
    val store: StoreSkuDto,
    val featured: Boolean = false,
    val badge: String? = null,
)

@Serializable
data class ChipOfferDto(
    val id: String,
    val title: String,
    val subtitle: String,
    val iconKey: String,
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
