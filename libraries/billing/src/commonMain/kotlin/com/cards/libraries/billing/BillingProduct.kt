package com.dangerfield.cards.libraries.billing

import kotlinx.serialization.Serializable

/**
 * A single product as the platform store knows it. The result of
 * [BillingClient.queryProducts] is keyed by [sku].
 *
 * [displayPrice] is already localized by the store (currency symbol +
 * locale-appropriate format). The client never formats prices itself —
 * the store owns that.
 *
 * Both Apple and Google return amounts as integer micros / minor units;
 * we keep the localized string as the authoritative display value and
 * expose [priceMicros] separately for telemetry / ledger purposes. Don't
 * format [priceMicros] for display — `priceMicros / 1_000_000` rounding
 * loses the long fractional digits that some currencies use and the
 * localized string handles all of that natively.
 */
@Serializable
data class BillingProduct(
    val sku: String,
    val displayPrice: String,
    val currencyCode: String,
    val priceMicros: Long,
)

/**
 * Outcome of [BillingClient.queryProducts]. Modeled as a sealed result so
 * the shop can distinguish "store said zero products" (real data, just
 * empty) from "store call failed" (retry-worthy).
 */
sealed interface QueryProductsResult {
    /**
     * Successful response. [products] is keyed by SKU. SKUs absent from
     * the map are SKUs the store doesn't recognize — drop them from the
     * catalog rather than retrying.
     */
    data class Success(val products: Map<String, BillingProduct>) : QueryProductsResult

    /** Platform billing connection isn't established yet. Reconnect + retry. */
    data object NotConnected : QueryProductsResult

    /**
     * Transient store error (network, billing-svc disabled, etc.).
     * Catch all platform-specific error codes here so screen code stays
     * single-branch.
     */
    data class Failed(val message: String) : QueryProductsResult
}
