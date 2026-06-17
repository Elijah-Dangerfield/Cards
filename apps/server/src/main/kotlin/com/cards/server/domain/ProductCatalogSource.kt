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

    /**
     * Look up a single product by id, bypassing the shop-catalog filter.
     *
     * `read()` deliberately omits unlock-only rows so they can never leak
     * into the shop. But code paths that *render* a user's unlock-only
     * holdings (Trophy Case, inventory-grant on achievement reward) need
     * to resolve a known id back to its [Product] regardless of the flag.
     *
     * Returns null when no row with the given id exists. Context is
     * accepted for the same forward-compat reason as [read] — a future
     * impl may want to vary by platform / locale — even though the current
     * impl doesn't filter by it.
     */
    suspend fun readById(
        id: String,
        context: com.dangerfield.cards.server.http.ClientContext,
    ): Product?
}

/** Catalog payload — what the endpoint serializes after localization. */
data class ProductCatalog(
    val chipPacks: List<Product.ChipPack>,
    val chipOffers: List<Product.ChipOffer>,
    /**
     * `unlock_only` cosmetics (badges + titles) — the prestige grants the shop
     * filter omits from [chipOffers]. Surfaced separately so clients can resolve
     * an *owned* badge/title's display metadata (name, description, emoji)
     * without the shop ever rendering them as buyable. Reuses [Product.ChipOffer]
     * since the shape is identical; they just never appear in a shop shelf.
     */
    val prestige: List<Product.ChipOffer> = emptyList(),
)
