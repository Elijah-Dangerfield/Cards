package com.dangerfield.cards.libraries.billing

import kotlinx.serialization.Serializable

/**
 * Outcome of [BillingClient.purchase]. Every branch carries the data the
 * UI needs to render the right toast / animation; the screen never
 * inspects platform-specific error codes.
 */
sealed interface PurchaseResult {
    /**
     * Store charged the user and returned a receipt. [transaction]
     * carries everything the server needs to validate the purchase
     * (orderId, purchaseToken, productId, etc.). The chip grant is
     * server-side: the client POSTs [transaction] to `/v1/billing/redeem`
     * and the server credits the user's ledger after verifying the
     * receipt with the platform.
     */
    data class Success(val transaction: PurchaseTransaction) : PurchaseResult

    /** User dismissed the store sheet without completing. No toast — silent. */
    data object UserCancelled : PurchaseResult

    /**
     * Store reports this SKU is already owned by this account. Treat as
     * idempotent: re-trigger the chip grant via `/v1/billing/redeem` so a
     * client that lost track of a purchase recovers automatically.
     */
    data class AlreadyOwned(val transaction: PurchaseTransaction) : PurchaseResult

    /**
     * Store can't process this purchase right now (network, billing-svc
     * down, account ineligible, etc.). Surface a generic retry toast.
     */
    data class Failed(val reason: String) : PurchaseResult

    /** Billing client isn't connected to the platform store yet. */
    data object NotConnected : PurchaseResult
}

/**
 * Receipt + metadata for a finished purchase, ready to POST to the
 * server for validation + chip grant. The server cross-references
 * [purchaseToken] against the platform's purchase-state API to confirm
 * the user actually paid before crediting their ledger.
 *
 * [platform] is included on the wire so the server picks the right
 * verification provider (Apple's `verifyReceipt` vs Google Play
 * Developer API).
 */
@Serializable
data class PurchaseTransaction(
    val sku: String,
    /** Platform-issued order id (Apple `transaction_id`, Google `orderId`). */
    val orderId: String,
    /**
     * Opaque token the server submits to the platform's validation API.
     * Apple: the base64 receipt payload. Google: the
     * [purchaseToken][com.android.billingclient.api.Purchase.getPurchaseToken].
     */
    val purchaseToken: String,
    val platform: BillingPlatform,
    /** Epoch ms when the platform issued the receipt. */
    val purchasedAtEpochMs: Long,
    /**
     * Localized price the user actually paid. Echoed from the store
     * response. Server doesn't trust this — it re-fetches the
     * authoritative amount during receipt validation — but it's
     * useful for ledger display + telemetry pre-validation.
     */
    val displayPrice: String? = null,
)

@Serializable
enum class BillingPlatform {
    Apple,
    Google,
    /**
     * Used by [com.dangerfield.cards.libraries.billing.impl.FakeBillingClient]
     * to mark transactions that didn't come from a real store. The server
     * rejects [Fake] receipts in production builds; dev builds let them
     * through so we can exercise the redemption flow without sandbox
     * accounts.
     */
    Fake,
}
