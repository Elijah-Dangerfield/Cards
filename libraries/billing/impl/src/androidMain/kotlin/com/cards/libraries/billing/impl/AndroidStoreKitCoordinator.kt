package com.dangerfield.cards.libraries.billing.impl

import com.dangerfield.cards.libraries.billing.StoreKitCoordinator
import com.dangerfield.cards.libraries.billing.StoreKitProduct
import com.dangerfield.cards.libraries.billing.StoreKitPurchaseResult
import com.dangerfield.cards.libraries.billing.StoreKitPurchaseStatus
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Android binding for [StoreKitCoordinator]. StoreKit is iOS-only, so this never
 * runs in practice — Android IAP goes through [RealPlayBillingClient]. It exists
 * so the Android DI graph has a binding for the type. The iOS graph gets the
 * real Swift coordinator via `IosAppComponent`.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidStoreKitCoordinator : StoreKitCoordinator {
    override fun loadProducts(
        productIds: List<String>,
        onComplete: (products: List<StoreKitProduct>, errorMessage: String?) -> Unit,
    ) = onComplete(emptyList(), "StoreKit is available on iOS only")

    override fun purchase(
        productId: String,
        appAccountToken: String,
        onComplete: (result: StoreKitPurchaseResult) -> Unit,
    ) = onComplete(
        StoreKitPurchaseResult(
            status = StoreKitPurchaseStatus.Failed,
            errorMessage = "StoreKit is available on iOS only",
        ),
    )

    override fun finishTransaction(
        jwsRepresentation: String,
        onComplete: (finished: Boolean) -> Unit,
    ) = onComplete(false)

    override fun loadUnfinishedTransactions(
        onComplete: (transactions: List<StoreKitPurchaseResult>) -> Unit,
    ) = onComplete(emptyList())
}
