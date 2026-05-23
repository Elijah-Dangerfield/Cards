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
 * **Default binding is
 * [com.dangerfield.cards.libraries.billing.impl.DevBillingClient]** while
 * we don't yet have provisioned store listings. In debug builds it
 * delegates to a [com.dangerfield.cards.libraries.billing.impl.FakeBillingClient]
 * seeded with the chip-pack SKUs we plan to ship, so the shop renders
 * its IAP tiles end-to-end. In release builds it falls back to
 * [com.dangerfield.cards.libraries.billing.impl.NoOpBillingClient]
 * behavior — empty product map, `Disconnected` connection state — so
 * release users see only chip-funded offers (the "store listings not
 * provisioned" baseline).
 *
 * To enable real IAP, swap the binding to a `PlayBillingClient` (Android)
 * or `StoreKitBillingClient` (iOS) impl with
 * `@ContributesBinding(replaces = [DevBillingClient::class])` — and
 * delete `DevBillingClient` while you're at it.
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
     * [userId] is forwarded to the store as an "obfuscated account id"
     * so a server-side receipt validation pass can pin the purchase to
     * the right player. The store doesn't echo this back in the receipt
     * payload — server validation looks it up from the receipt's
     * orderId via the platform's purchase-state API.
     */
    suspend fun purchase(sku: String, userId: String): PurchaseResult

    /**
     * Acknowledge a successful purchase. On Android, unacknowledged
     * purchases are auto-refunded by the Play Store after 3 days — call
     * this once the server has granted the chips on its side. iOS has
     * no equivalent timer; the call is a no-op on that platform.
     */
    suspend fun acknowledge(purchaseToken: String): Boolean
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
