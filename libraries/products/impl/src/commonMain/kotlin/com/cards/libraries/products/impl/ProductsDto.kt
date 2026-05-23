package com.dangerfield.cards.libraries.products.impl

import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.products.ProductCatalog
import com.dangerfield.cards.libraries.products.StoreSku
import kotlinx.serialization.Serializable

/**
 * Wire format for `GET /v1/products`. Mirrors the server's response shape.
 * Kept separate from the public domain model so the wire format can evolve
 * (`schemaVersion` bumps, new fields, etc.) without API consumers having to
 * change anything.
 *
 * `kotlinx.serialization` defaults: missing optional fields fall back to
 * their default values, unknown server-added keys are ignored (in release;
 * strict-throws in debug — see `NetworkJson`).
 */
@Serializable
data class ProductCatalogDto(
    val schemaVersion: Int = 1,
    /** Server's wall clock at response time. See server's ProductCatalogResponse
     *  and client's [com.dangerfield.cards.libraries.products.CatalogTimeAnchor]. */
    val serverNowEpochMs: Long = 0L,
    val chipPacks: List<ChipPackDto> = emptyList(),
    val chipOffers: List<ChipOfferDto> = emptyList(),
)

@Serializable
data class ChipPackDto(
    val id: String,
    val title: String,
    val subtitle: String,
    val iconEmoji: String,
    val grantsChips: Long,
    val store: StoreSkuDto,
    val featured: Boolean = false,
    val badge: String? = null,
    val availableUntilEpochMs: Long? = null,
    val isEquippable: Boolean = false,
)

@Serializable
data class ChipOfferDto(
    val id: String,
    val title: String,
    val subtitle: String,
    val iconEmoji: String,
    val costChips: Long,
    val grantsKey: String,
    val featured: Boolean = false,
    val badge: String? = null,
    val description: String? = null,
    val unlockLevel: Int? = null,
    val availableUntilEpochMs: Long? = null,
    val isEquippable: Boolean = false,
)

@Serializable
data class StoreSkuDto(
    val sku: String,
    val fallbackPriceDisplay: String,
)

fun ProductCatalogDto.toDomain(): ProductCatalog = ProductCatalog(
    chipPacks = chipPacks.map { it.toDomain() },
    chipOffers = chipOffers.map { it.toDomain() },
)

private fun ChipPackDto.toDomain(): Product.ChipPack = Product.ChipPack(
    id = id,
    title = title,
    subtitle = subtitle,
    iconEmoji = iconEmoji,
    grantsChips = grantsChips,
    store = store.toDomain(),
    featured = featured,
    badge = badge,
    availableUntilEpochMs = availableUntilEpochMs,
    isEquippable = isEquippable,
)

private fun ChipOfferDto.toDomain(): Product.ChipOffer = Product.ChipOffer(
    id = id,
    title = title,
    subtitle = subtitle,
    iconEmoji = iconEmoji,
    costChips = costChips,
    grantsKey = grantsKey,
    featured = featured,
    badge = badge,
    description = description,
    unlockLevel = unlockLevel,
    availableUntilEpochMs = availableUntilEpochMs,
    isEquippable = isEquippable,
)

private fun StoreSkuDto.toDomain(): StoreSku = StoreSku(
    sku = sku,
    fallbackPriceDisplay = fallbackPriceDisplay,
)
