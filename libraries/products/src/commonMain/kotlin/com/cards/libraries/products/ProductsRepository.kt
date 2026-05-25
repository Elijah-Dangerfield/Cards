package com.dangerfield.cards.libraries.products

import kotlinx.coroutines.flow.Flow

/**
 * Source of the shop catalog for the rest of the app.
 *
 * Designed for the screen-load lifecycle:
 *  - [observeCatalog] is the live feed UI subscribes to — emits the current
 *    cached catalog plus future refreshes.
 *  - [refresh] either kicks a network fetch and updates the cached value
 *    (and therefore the flow), or no-ops when the cache is still warm.
 *    UI calls this on screen entry (`force = false`) and from pull-to-
 *    refresh (`force = true`).
 *
 * Caching: in-memory, single-flight. Two callers asking for a refresh during
 * an in-flight request share the result. A successful refresh marks the cache
 * fresh; subsequent non-forced refreshes within the implementation's TTL
 * window short-circuit and return the cached catalog without hitting the
 * network. Catalog turnover is on the order of days, so re-pulling on every
 * screen entry burned bandwidth for no user-visible benefit.
 *
 * Errors propagate as [Result] from [refresh] — exceptions don't escape the
 * suspend boundary, matching the rest of the codebase's `Catching { }` style.
 */
interface ProductsRepository {

    /** Live catalog feed. Emits the cached value on subscribe; then updates. */
    fun observeCatalog(): Flow<ProductCatalog>

    /**
     * Refresh the catalog.
     *
     * @param force when true, always hit the network. When false, short-
     *   circuits with the cached catalog if the previous successful fetch
     *   is still within the implementation's freshness window. Pull-to-
     *   refresh should pass `force = true`; screen-entry should pass `false`.
     */
    suspend fun refresh(force: Boolean = false): Result<ProductCatalog>

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
