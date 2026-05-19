package com.dangerfield.cards.libraries.billing

import kotlinx.coroutines.flow.Flow

/**
 * Caches the most recent [BillingClient.queryProducts] result so the
 * products repository can synchronously read available SKUs while
 * filtering the catalog.
 *
 * Why this exists vs calling the billing client directly: the catalog
 * refresh path is suspending and already does network work — we don't
 * want it to also wait on a fresh store query every time, especially
 * since users may pull-to-refresh more often than the store catalog
 * changes. This caches one query at a time and lets the catalog
 * pipeline read from it cheaply.
 *
 * Refresh policy: `refresh(skus)` is single-flight (concurrent callers
 * share the result). The shop screen kicks one on mount; products
 * repo can request another after a server catalog response brings in
 * a new SKU.
 */
interface BillingAvailability {

    /** Most recent successful product fetch, keyed by SKU. */
    val products: Flow<Map<String, BillingProduct>>

    /** Latest connection state from the underlying [BillingClient]. */
    val connectionState: Flow<ConnectionState>

    /**
     * Fetch (or re-fetch) [skus] from the store. Single-flight across
     * concurrent callers. Result is also pushed onto [products].
     *
     * Returns the underlying [QueryProductsResult] so callers that want
     * to differentiate "store said no" from "store call failed" can.
     */
    suspend fun refresh(skus: Set<String>): QueryProductsResult

    /**
     * Synchronous snapshot of the most recent product map. Used by the
     * catalog reconciliation pass (which can't suspend mid-emit).
     * Empty when nothing has been fetched yet OR the most recent query
     * failed.
     */
    fun snapshot(): Map<String, BillingProduct>
}
