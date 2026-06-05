package com.dangerfield.cards.libraries.billing.impl

import com.dangerfield.cards.libraries.billing.BillingClient
import com.dangerfield.cards.libraries.billing.BillingProduct
import com.dangerfield.cards.libraries.billing.ConnectionState
import com.dangerfield.cards.libraries.billing.PurchaseResult
import com.dangerfield.cards.libraries.billing.QueryProductsResult
import com.dangerfield.cards.libraries.core.BuildInfo
import kotlinx.coroutines.flow.StateFlow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Temporary [BillingClient] binding for dev iteration on the shop UI.
 *
 * Replaces [NoOpBillingClient] in the graph. At runtime:
 *  - **Debug builds** delegate to a [FakeBillingClient] seeded with the
 *    chip-pack SKUs the server seeds in V5__products.sql. Purchases
 *    succeed with a synthesized [PurchaseTransaction] so the redemption
 *    flow can be exercised end-to-end without real money or sandbox
 *    accounts.
 *  - **Release builds** fall back to [NoOpBillingClient] behavior so we
 *    don't ship pretend products to real users. (Concretely: same
 *    "store listings not provisioned" baseline the shop had before
 *    this binding existed.)
 *
 * TODO(billing): remove this class once a real platform binding lands.
 *  When `PlayBillingClient` / `StoreKitBillingClient` ship, replace the
 *  `replaces = [NoOpBillingClient::class]` here with a per-platform
 *  binding using `replaces = [DevBillingClient::class]` and delete
 *  this file. The hardcoded catalog below drifts the moment the seed
 *  SKUs in `V5__products.sql` change; don't leave it around longer
 *  than needed.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [NoOpBillingClient::class])
@Inject
class DevBillingClient : BillingClient {

    private val delegate: BillingClient = if (BuildInfo.isDebug) {
        FakeBillingClient(catalog = DEV_FAKE_CATALOG)
    } else {
        NoOpBillingClient()
    }

    override val connectionState: StateFlow<ConnectionState> = delegate.connectionState
    override suspend fun connect(): ConnectionState = delegate.connect()
    override suspend fun queryProducts(skus: Set<String>): QueryProductsResult = delegate.queryProducts(skus)
    override suspend fun purchase(sku: String, userId: String): PurchaseResult = delegate.purchase(sku, userId)
    override suspend fun acknowledge(purchaseToken: String): Boolean = delegate.acknowledge(purchaseToken)
}

/**
 * Pretend store catalog mirroring the chip-pack rows seeded in
 * `apps/server/src/main/resources/db/migration/V5__products.sql`.
 * Includes both iOS and Android SKUs for each pack so the dev client
 * is platform-agnostic — the server picks one per request, but we
 * don't know which one at compile time.
 *
 * Prices match the server-side `fallback_price` columns so the shop
 * tile renders the same number in dev as it will once the platform
 * store reports real prices.
 */
private val DEV_FAKE_CATALOG: Map<String, BillingProduct> = listOf(
    devProduct(sku = "com.cards.iap.chips.small", price = "$0.99", micros = 990_000),
    devProduct(sku = "chips_small", price = "$0.99", micros = 990_000),
    devProduct(sku = "com.cards.iap.chips.medium", price = "$4.99", micros = 4_990_000),
    devProduct(sku = "chips_medium", price = "$4.99", micros = 4_990_000),
    devProduct(sku = "com.cards.iap.chips.large", price = "$14.99", micros = 14_990_000),
    devProduct(sku = "chips_large", price = "$14.99", micros = 14_990_000),
).associateBy { it.sku }

private fun devProduct(sku: String, price: String, micros: Long): BillingProduct = BillingProduct(
    sku = sku,
    displayPrice = price,
    currencyCode = "USD",
    priceMicros = micros,
)
