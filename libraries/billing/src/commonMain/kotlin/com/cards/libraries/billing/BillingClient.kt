package com.dangerfield.cards.libraries.billing

import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over the platform's in-app purchase system (Play Billing on
 * Android, StoreKit on iOS). The shop screen never touches the store
 * directly — it goes through this interface so screen code is provider-
 * agnostic and previewable.
 *
 * Conceptually mirrors how [com.dangerfield.cards.libraries.identity.auth.AuthRepository]
 * hides Supabase from feature code: the interface lives in the api
 * module; the [SingleIn] platform implementation lives in `impl` and is
 * provided per-platform via build flavors.
 *
 * Lifecycle: [connect] is idempotent — call it whenever the shop screen
 * mounts. [disconnect] is intentionally absent for V1 because the Play
 * Billing client costs ~nothing to keep connected and the platform
 * frameworks own teardown on process exit.
 *
 * Bound per platform: `PlayBillingClient` (Android) and
 * `StoreKitBillingClient` (iOS). Both select their delegate at
 * construction — debug builds get a
 * [com.dangerfield.cards.libraries.billing.impl.FakeBillingClient]
 * seeded with the chip-pack SKUs so the shop renders its IAP tiles
 * end-to-end without provisioned listings; release builds get the real
 * Play Billing / StoreKit 2 client.
 *
 * Errors: every call returns a sealed result type rather than throwing,
 * because callers want to render specific UI for "user cancelled" vs
 * "already owned" vs "store unavailable." Try/catch at every site was
 * the worse alternative.
 */
interface BillingClient {

    /**
     * Lifecycle state of the underlying platform billing connection.
     * UI uses this to decide whether to gate the IAP packs section or
     * surface a "store unavailable" banner.
     */
    val connectionState: StateFlow<ConnectionState>

    /** Idempotent — safe to call from shop init each time. */
    suspend fun connect(): ConnectionState

    /**
     * Query the platform store for the listed SKUs. Returns one
     * [BillingProduct] per SKU the store recognizes; missing SKUs are
     * simply absent from the result map (no exception).
     *
     * The store is the source of truth for the localized price string,
     * currency code, and whether the SKU is actually purchasable. We use
     * the result to filter our backend catalog down + overlay real
     * prices on `StoreSku.fallbackPriceDisplay`.
     */
    suspend fun queryProducts(skus: Set<String>): QueryProductsResult

    /**
     * Launch the platform purchase flow for [sku]. Suspends until the
     * user finishes interacting with the store sheet (success, cancel,
     * already-owned, etc.).
     *
     * [userId] is forwarded to the store as an account token (StoreKit
     * `appAccountToken` / Play `setObfuscatedAccountId`) so a server-side
     * receipt validation pass can pin the purchase to the right player. The
     * store echoes this token back in the verified receipt — the StoreKit 2
     * transaction's `appAccountToken` and the Play purchase's
     * `obfuscatedExternalAccountId` — and server validation requires it to
     * equal the authenticated caller. Pinning to the order id instead would
     * let one user redeem another's receipt.
     */
    suspend fun purchase(sku: String, userId: String): PurchaseResult

    /**
     * Acknowledge a successful purchase. On Android, unacknowledged
     * purchases are auto-refunded by the Play Store after 3 days — call
     * this once the server has granted the chips on its side. iOS has
     * no equivalent timer; the call is a no-op on that platform.
     *
     * Use this only for **non-consumable / durable** entitlements. Chip
     * packs are consumables — call [consume] instead, which acknowledges
     * *and* clears the entitlement so the same pack can be bought again.
     */
    suspend fun acknowledge(purchaseToken: String): Boolean

    /**
     * Consume a finished **consumable** purchase so the user can buy the
     * same SKU again. Chip packs are consumables: without this, Play
     * reports the SKU as already-owned on the next purchase attempt and a
     * re-buy is impossible.
     *
     * Platform mapping:
     *  - **Android:** `BillingClient.consumeAsync(purchaseToken)`. This
     *    both acknowledges the purchase (satisfying the 3-day auto-refund
     *    timer) and consumes it, so a separate [acknowledge] is redundant.
     *  - **iOS:** StoreKit auto-finishing for consumables happens via
     *    `transaction.finish()`; this call routes there. The
     *    [purchaseToken] doubles as the StoreKit transaction id.
     *
     * Call this only after the server has granted the chips, so a crash
     * between grant and consume re-grants idempotently rather than
     * dropping a paid purchase.
     */
    suspend fun consume(purchaseToken: String): Boolean
}

enum class ConnectionState {
    /** Initial state — [BillingClient.connect] hasn't run yet. */
    Disconnected,

    /** [BillingClient.connect] in progress. */
    Connecting,

    /** Platform store is reachable and queries can be issued. */
    Connected,

    /**
     * The platform reported a permanent failure: device unsupported,
     * Play Services missing, or the store account is unavailable. UI
     * should hide IAP packs entirely rather than retry.
     */
    Unavailable,
}
