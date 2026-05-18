package com.dangerfield.cards.libraries.products

import kotlinx.coroutines.flow.Flow

/**
 * Source of the shop catalog for the rest of the app.
 *
 * Designed for the screen-load lifecycle:
 *  - [observeCatalog] is the live feed UI subscribes to — emits the current
 *    cached catalog plus future refreshes.
 *  - [refresh] kicks a network fetch and updates the cached value (and
 *    therefore the flow). UI calls this from `init` and from pull-to-refresh.
 *
 * Caching: in-memory, single-flight. Two callers asking for a refresh during
 * an in-flight request share the result.
 *
 * Errors propagate as [Result] from [refresh] — exceptions don't escape the
 * suspend boundary, matching the rest of the codebase's `Catching { }` style.
 */
interface ProductsRepository {

    /** Live catalog feed. Emits the cached value on subscribe; then updates. */
    fun observeCatalog(): Flow<ProductCatalog>

    /** Force a network fetch. Caches the result on success. */
    suspend fun refresh(): Result<ProductCatalog>

    /**
     * Live feed of the time anchor associated with the most recent
     * successful fetch. Null until the first successful fetch lands.
     *
     * Subscribers use this to convert `Product.availableUntilEpochMs`
     * into clock-spoof-resistant remaining-time values. See
     * [CatalogTimeAnchor] for the math.
     */
    fun observeTimeAnchor(): Flow<CatalogTimeAnchor?>
}
