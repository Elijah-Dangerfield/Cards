package com.dangerfield.cards.libraries.billing.impl

import android.content.Context
import com.dangerfield.cards.libraries.billing.BillingClient
import com.dangerfield.cards.libraries.billing.ConnectionState
import com.dangerfield.cards.libraries.billing.PurchaseResult
import com.dangerfield.cards.libraries.billing.QueryProductsResult
import com.dangerfield.cards.libraries.cards.ActivityProvider
import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.flowroutines.DispatcherProvider
import kotlinx.coroutines.flow.StateFlow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Android [BillingClient] binding. Replaces [DevBillingClient] in the graph,
 * completing the per-platform handoff the Dev client's TODO described.
 *
 * Runtime selection mirrors [DevBillingClient]:
 *  - **Debug builds** delegate to a [FakeBillingClient] seeded with the
 *    chip-pack SKUs, so dev shop iteration and the redemption flow keep
 *    working without provisioned Play listings or license testers.
 *  - **Release builds** delegate to [RealPlayBillingClient], which drives
 *    the live Play Billing Library.
 *
 * Once Play listings + license testers are provisioned (developer-gated),
 * flip debug to the real client too, or gate via the BILL-5
 * `billing.realPurchasesEnabled` flag if a finer switch is wanted.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DevBillingClient::class, NoOpBillingClient::class])
@Inject
class PlayBillingClient(
    context: Context,
    activityProvider: ActivityProvider,
    dispatchers: DispatcherProvider,
) : BillingClient {

    private val delegate: BillingClient = if (BuildInfo.isDebug) {
        FakeBillingClient(catalog = DEV_FAKE_CATALOG)
    } else {
        RealPlayBillingClient(context, activityProvider, dispatchers)
    }

    override val connectionState: StateFlow<ConnectionState> = delegate.connectionState
    override suspend fun connect(): ConnectionState = delegate.connect()
    override suspend fun queryProducts(skus: Set<String>): QueryProductsResult = delegate.queryProducts(skus)
    override suspend fun purchase(sku: String, userId: String): PurchaseResult = delegate.purchase(sku, userId)
    override suspend fun acknowledge(purchaseToken: String): Boolean = delegate.acknowledge(purchaseToken)
    override suspend fun consume(purchaseToken: String): Boolean = delegate.consume(purchaseToken)
}
