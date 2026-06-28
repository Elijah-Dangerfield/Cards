package com.dangerfield.cards.libraries.billing.impl

import com.dangerfield.cards.libraries.billing.BillingClient
import com.dangerfield.cards.libraries.billing.ConnectionState
import com.dangerfield.cards.libraries.billing.PurchaseResult
import com.dangerfield.cards.libraries.billing.QueryProductsResult
import com.dangerfield.cards.libraries.billing.StoreKitCoordinator
import com.dangerfield.cards.libraries.billing.awaitFinish
import com.dangerfield.cards.libraries.billing.awaitProducts
import com.dangerfield.cards.libraries.billing.awaitPurchase
import com.dangerfield.cards.libraries.billing.toBillingProduct
import com.dangerfield.cards.libraries.billing.toPurchaseResult
import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.Catching
import kotlinx.coroutines.flow.MutableStateFlow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * iOS [BillingClient] binding. Replaces [DevBillingClient] in the graph,
 * completing the per-platform handoff its TODO described — the mirror of
 * [PlayBillingClient] on Android.
 *
 * Runtime selection mirrors [PlayBillingClient]:
 *  - **Debug builds** delegate to a [FakeBillingClient] seeded with the
 *    chip-pack SKUs, so dev shop iteration and the redemption flow keep working
 *    without provisioned App Store Connect listings or a sandbox account.
 *  - **Release builds** delegate to [RealStoreKitBillingClient], which drives
 *    the native StoreKit 2 flow through the Swift [StoreKitCoordinator].
 *
 * Locally you can exercise the real path against an Xcode `.storekit` test
 * config without App Store Connect — flip debug to the real client there, or
 * gate via the BILL-5 `billing.realPurchasesEnabled` flag if a finer switch is
 * wanted.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DevBillingClient::class, NoOpBillingClient::class])
@Inject
class StoreKitBillingClient(
    coordinator: StoreKitCoordinator,
) : BillingClient {

    private val delegate: BillingClient = if (BuildInfo.isDebug) {
        FakeBillingClient(catalog = DEV_FAKE_CATALOG)
    } else {
        RealStoreKitBillingClient(coordinator)
    }

    override val connectionState = delegate.connectionState
    override suspend fun connect(): ConnectionState = delegate.connect()
    override suspend fun queryProducts(skus: Set<String>): QueryProductsResult = delegate.queryProducts(skus)
    override suspend fun purchase(sku: String, userId: String): PurchaseResult = delegate.purchase(sku, userId)
    override suspend fun acknowledge(purchaseToken: String): Boolean = delegate.acknowledge(purchaseToken)
    override suspend fun consume(purchaseToken: String): Boolean = delegate.consume(purchaseToken)
}

/**
 * Real StoreKit 2 [BillingClient]. Thin Kotlin shell over the Swift
 * [StoreKitCoordinator]: the native side owns `Product.products(for:)`,
 * `Product.purchase(options:)`, and `Transaction.finish()`; this maps the
 * coordinator's flattened results onto the [BillingClient] contract.
 *
 * StoreKit has no separate "connection" — products load on demand — so
 * [connect] reports [ConnectionState.Connected] unconditionally and
 * [queryProducts] surfaces the real availability (an empty/failed product load
 * is what gates the IAP section, not a connection state).
 *
 * Chip packs are consumables, so the round-trip ends in [consume]
 * (`Transaction.finish()`). [acknowledge] is a no-op on iOS — StoreKit has no
 * acknowledge timer — matching the api kdoc.
 *
 * The `userId` is forwarded to StoreKit as `appAccountToken`; StoreKit echoes it
 * back on the verified `Transaction.appAccountToken`, which the server pins
 * against the authenticated caller during receipt validation.
 */
internal class RealStoreKitBillingClient(
    private val coordinator: StoreKitCoordinator,
) : BillingClient {

    override val connectionState = MutableStateFlow(ConnectionState.Connected)

    override suspend fun connect(): ConnectionState = ConnectionState.Connected

    override suspend fun queryProducts(skus: Set<String>): QueryProductsResult {
        if (skus.isEmpty()) return QueryProductsResult.Success(emptyMap())
        return coordinator.awaitProducts(skus.toList()).fold(
            onSuccess = { products ->
                QueryProductsResult.Success(products.associate { it.productId to it.toBillingProduct() })
            },
            onFailure = { QueryProductsResult.Failed(it.message ?: "StoreKit product load failed") },
        )
    }

    override suspend fun purchase(sku: String, userId: String): PurchaseResult =
        coordinator.awaitPurchase(productId = sku, appAccountToken = userId).toPurchaseResult()

    override suspend fun acknowledge(purchaseToken: String): Boolean = true

    override suspend fun consume(purchaseToken: String): Boolean =
        Catching { coordinator.awaitFinish(purchaseToken) }.getOrDefault(false)
}
