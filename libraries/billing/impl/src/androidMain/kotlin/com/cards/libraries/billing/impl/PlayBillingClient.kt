package com.dangerfield.cards.libraries.billing.impl

import android.content.Context
import com.dangerfield.cards.libraries.billing.BillingClient
import com.dangerfield.cards.libraries.billing.ConnectionState
import com.dangerfield.cards.libraries.billing.PurchaseResult
import com.dangerfield.cards.libraries.billing.QueryProductsResult
import com.dangerfield.cards.libraries.billing.RealPurchasesEnabled
import com.dangerfield.cards.libraries.cards.ActivityProvider
import com.dangerfield.cards.libraries.flowroutines.DispatcherProvider
import kotlinx.coroutines.flow.StateFlow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Android [BillingClient] binding, the mirror of `StoreKitBillingClient`
 * on iOS.
 *
 * Runtime selection follows the BILL-5 `billing.realPurchasesEnabled` flag,
 * resolved per call so a config change or QA override applies without a
 * restart:
 *  - **On (default)** — [RealPlayBillingClient], which drives the live Play
 *    Billing Library. Requires provisioned Play listings (+ license testers
 *    for free test purchases).
 *  - **Off** — a [FakeBillingClient] seeded with the chip-pack SKUs, for shop
 *    iteration without provisioned Play listings.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class PlayBillingClient(
    context: Context,
    activityProvider: ActivityProvider,
    dispatchers: DispatcherProvider,
    private val realPurchasesEnabled: RealPurchasesEnabled,
) : BillingClient {

    private val fake = FakeBillingClient(catalog = DEV_FAKE_CATALOG)
    private val real by lazy { RealPlayBillingClient(context, activityProvider, dispatchers) }

    private fun delegate(): BillingClient = if (realPurchasesEnabled()) real else fake

    override val connectionState: StateFlow<ConnectionState> get() = delegate().connectionState
    override suspend fun connect(): ConnectionState = delegate().connect()
    override suspend fun queryProducts(skus: Set<String>): QueryProductsResult = delegate().queryProducts(skus)
    override suspend fun purchase(sku: String, userId: String): PurchaseResult = delegate().purchase(sku, userId)
    override suspend fun acknowledge(purchaseToken: String): Boolean = delegate().acknowledge(purchaseToken)
    override suspend fun consume(purchaseToken: String): Boolean = delegate().consume(purchaseToken)
}
