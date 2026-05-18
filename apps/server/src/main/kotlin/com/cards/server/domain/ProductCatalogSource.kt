package com.dangerfield.cards.server.domain

/**
 * Source of the product catalog.
 *
 * Mirrors the [AppConfigSource] pattern: an in-memory impl for dev, swappable
 * for a database-backed impl in production with no endpoint changes (same
 * [@ContributesBinding] annotation, the new impl simply takes over).
 *
 * Catalog reads are scoped to the requesting client via [ClientContext] so
 * sources can vary their answer by platform / country / app version. For V1
 * the in-memory source returns the same catalog for everyone, but the
 * signature is in place so the next iteration (Phase 2: country-priced packs,
 * platform-exclusive promos) doesn't need a contract change.
 */
interface ProductCatalogSource {
    suspend fun read(context: com.dangerfield.cards.server.http.ClientContext): ProductCatalog
}

/** Catalog payload — what the endpoint serializes after localization. */
data class ProductCatalog(
    val chipPacks: List<Product.ChipPack>,
    val chipOffers: List<Product.ChipOffer>,
)
