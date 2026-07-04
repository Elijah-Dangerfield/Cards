package com.dangerfield.cards.libraries.billing.impl

import com.dangerfield.cards.libraries.billing.BillingClient
import com.dangerfield.cards.libraries.billing.BillingPlatform
import com.dangerfield.cards.libraries.billing.BillingProduct
import com.dangerfield.cards.libraries.billing.ConnectionState
import com.dangerfield.cards.libraries.billing.PurchaseResult
import com.dangerfield.cards.libraries.billing.PurchaseTransaction
import com.dangerfield.cards.libraries.billing.QueryProductsResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pretends to be a real platform store. Useful for:
 *  - QA builds where we want to exercise the redemption flow without
 *    provisioning sandbox accounts on Apple / Google.
 *  - Compose previews + unit tests that need the IAP packs visible.
 *  - Demo / screenshot builds.
 *
 * Construct with a static product catalog. Every purchase succeeds with
 * a generated [PurchaseTransaction] tagged [BillingPlatform.Fake], so
 * the server can drop these on the floor in production. Force specific
 * outcomes per-SKU via [outcomesBySku] when testing the cancel /
 * already-owned / failed branches.
 *
 * Not annotated with `@ContributesBinding` — the platform bindings
 * (`PlayBillingClient` / `StoreKitBillingClient`) construct it directly
 * for debug builds; wire it manually anywhere else.
 */
class FakeBillingClient(
    private val catalog: Map<String, BillingProduct>,
    private val outcomesBySku: Map<String, FakeOutcome> = emptyMap(),
    private val nowEpochMs: () -> Long = { 0L },
) : BillingClient {

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var sequence = 0L

    override suspend fun connect(): ConnectionState {
        _connectionState.value = ConnectionState.Connected
        return ConnectionState.Connected
    }

    override suspend fun queryProducts(skus: Set<String>): QueryProductsResult {
        if (_connectionState.value != ConnectionState.Connected) {
            return QueryProductsResult.NotConnected
        }
        val filtered = catalog.filterKeys { it in skus }
        return QueryProductsResult.Success(products = filtered)
    }

    override suspend fun purchase(sku: String, userId: String): PurchaseResult {
        if (_connectionState.value != ConnectionState.Connected) {
            return PurchaseResult.NotConnected
        }
        val product = catalog[sku]
            ?: return PurchaseResult.Failed("Unknown SKU: $sku")
        return when (val forced = outcomesBySku[sku]) {
            FakeOutcome.Cancel -> PurchaseResult.UserCancelled
            FakeOutcome.AlreadyOwned -> PurchaseResult.AlreadyOwned(transactionFor(product))
            is FakeOutcome.Fail -> PurchaseResult.Failed(forced.reason)
            null, FakeOutcome.Succeed -> PurchaseResult.Success(transactionFor(product))
        }
    }

    override suspend fun acknowledge(purchaseToken: String): Boolean = true

    override suspend fun consume(purchaseToken: String): Boolean = true

    private fun transactionFor(product: BillingProduct): PurchaseTransaction {
        sequence += 1
        return PurchaseTransaction(
            sku = product.sku,
            orderId = "fake-order-${product.sku}-$sequence",
            purchaseToken = "fake-token-${product.sku}-$sequence",
            platform = BillingPlatform.Fake,
            purchasedAtEpochMs = nowEpochMs(),
            displayPrice = product.displayPrice,
        )
    }

    sealed interface FakeOutcome {
        data object Succeed : FakeOutcome
        data object Cancel : FakeOutcome
        data object AlreadyOwned : FakeOutcome
        data class Fail(val reason: String) : FakeOutcome
    }
}

/**
 * Pretend store catalog mirroring the chip-pack rows seeded in
 * `apps/server/src/main/resources/db/migration/V5__products.sql`.
 * Includes both iOS and Android SKUs for each pack so the fake catalog
 * is platform-agnostic — the server picks one per request, but we
 * don't know which one at compile time.
 *
 * Prices match the server-side `fallback_price` columns so the shop
 * tile renders the same number in dev as it will once the platform
 * store reports real prices.
 */
internal val DEV_FAKE_CATALOG: Map<String, BillingProduct> = listOf(
    fakeProduct(sku = "com.cards.iap.chips.small", price = "$0.99", micros = 990_000),
    fakeProduct(sku = "chips_small", price = "$0.99", micros = 990_000),
    fakeProduct(sku = "com.cards.iap.chips.medium", price = "$4.99", micros = 4_990_000),
    fakeProduct(sku = "chips_medium", price = "$4.99", micros = 4_990_000),
    fakeProduct(sku = "com.cards.iap.chips.large", price = "$14.99", micros = 14_990_000),
    fakeProduct(sku = "chips_large", price = "$14.99", micros = 14_990_000),
).associateBy { it.sku }

private fun fakeProduct(sku: String, price: String, micros: Long): BillingProduct = BillingProduct(
    sku = sku,
    displayPrice = price,
    currencyCode = "USD",
    priceMicros = micros,
)
