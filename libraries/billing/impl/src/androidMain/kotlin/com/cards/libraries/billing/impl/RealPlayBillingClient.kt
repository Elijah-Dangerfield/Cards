package com.dangerfield.cards.libraries.billing.impl

import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient as PlayBilling
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import com.dangerfield.cards.libraries.billing.BillingClient
import com.dangerfield.cards.libraries.billing.BillingPlatform
import com.dangerfield.cards.libraries.billing.BillingProduct
import com.dangerfield.cards.libraries.billing.ConnectionState
import com.dangerfield.cards.libraries.billing.PurchaseResult
import com.dangerfield.cards.libraries.billing.PurchaseTransaction
import com.dangerfield.cards.libraries.billing.QueryProductsResult
import com.dangerfield.cards.libraries.cards.ActivityProvider
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.DispatcherProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Real Play Billing Library (v9) implementation of [BillingClient].
 *
 * Chip packs are **consumables**: the purchase round-trip ends in [consume]
 * (Play `consumeAsync`), which both satisfies the 3-day acknowledge window
 * and clears the entitlement so the user can re-buy the same pack. A separate
 * [acknowledge] exists for durable products but isn't used by the chip flow.
 *
 * The `userId` is forwarded to Play as `setObfuscatedAccountId`; Play echoes
 * it back on the verified purchase as `obfuscatedExternalAccountId`, which the
 * server pins against the authenticated caller during receipt validation.
 *
 * Mechanics:
 *  - [connect] starts the Play connection and is idempotent — the client is
 *    reused once `Connected`.
 *  - [purchase] needs the foreground Activity for `launchBillingFlow`; it comes
 *    from the host-app [ActivityProvider]. The flow is callback-based (Play
 *    pushes results to the [PurchasesUpdatedListener]), bridged to a suspend
 *    result via a [CompletableDeferred]. A [Mutex] serialises purchases so
 *    concurrent taps can't cross their result callbacks.
 */
internal class RealPlayBillingClient(
    private val context: Context,
    private val activityProvider: ActivityProvider,
    private val dispatchers: DispatcherProvider,
) : BillingClient {

    private val logger = KLog.withTag("PlayBillingClient")

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val purchaseMutex = Mutex()
    private var pendingPurchase: CompletableDeferred<PurchaseResult>? = null

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        pendingPurchase?.complete(result.toPurchaseResult(purchases))
    }

    private val billing: PlayBilling by lazy {
        PlayBilling.newBuilder(context)
            .setListener(purchasesListener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
            )
            .build()
    }

    override suspend fun connect(): ConnectionState {
        if (_connectionState.value == ConnectionState.Connected && billing.isReady) {
            return ConnectionState.Connected
        }
        _connectionState.value = ConnectionState.Connecting
        val state = withContext(dispatchers.io) {
            suspendCancellableCoroutine { cont ->
                billing.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (!cont.isActive) return
                        cont.resume(
                            if (result.responseCode == PlayBilling.BillingResponseCode.OK) {
                                ConnectionState.Connected
                            } else {
                                ConnectionState.Unavailable
                            },
                        )
                    }

                    override fun onBillingServiceDisconnected() {
                        _connectionState.value = ConnectionState.Disconnected
                    }
                })
            }
        }
        _connectionState.value = state
        return state
    }

    override suspend fun queryProducts(skus: Set<String>): QueryProductsResult {
        if (!billing.isReady) return QueryProductsResult.NotConnected
        if (skus.isEmpty()) return QueryProductsResult.Success(emptyMap())

        return Catching {
            withContext(dispatchers.io) { billing.queryProductDetails(productDetailsParams(skus)) }
        }.fold(
            onSuccess = { result ->
                if (result.billingResult.responseCode != PlayBilling.BillingResponseCode.OK) {
                    QueryProductsResult.Failed(result.billingResult.debugMessage)
                } else {
                    val products = result.productDetailsList.orEmpty()
                        .mapNotNull { it.toBillingProduct() }
                        .associateBy { it.sku }
                    QueryProductsResult.Success(products)
                }
            },
            onFailure = { QueryProductsResult.Failed(it.message ?: "queryProducts failed") },
        )
    }

    override suspend fun purchase(sku: String, userId: String): PurchaseResult = purchaseMutex.withLock {
        if (!billing.isReady) return PurchaseResult.NotConnected
        val activity = activityProvider.currentActivity()
            ?: return PurchaseResult.Failed("No foreground Activity for the purchase flow")

        val details = Catching {
            withContext(dispatchers.io) { billing.queryProductDetails(productDetailsParams(setOf(sku))) }
        }.getOrNull()?.productDetailsList?.firstOrNull()
            ?: return PurchaseResult.Failed("Unknown SKU: $sku")

        val deferred = CompletableDeferred<PurchaseResult>()
        pendingPurchase = deferred

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build(),
                ),
            )
            .setObfuscatedAccountId(userId)
            .build()

        val launch = withContext(dispatchers.main) {
            billing.launchBillingFlow(activity, flowParams)
        }
        if (launch.responseCode != PlayBilling.BillingResponseCode.OK) {
            pendingPurchase = null
            return launch.toPurchaseResult(purchases = null)
        }

        return deferred.await().also { pendingPurchase = null }
    }

    override suspend fun acknowledge(purchaseToken: String): Boolean {
        if (!billing.isReady) return false
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build()
        return Catching {
            withContext(dispatchers.io) { billing.acknowledgePurchase(params) }
        }.getOrNull()?.responseCode == PlayBilling.BillingResponseCode.OK
    }

    override suspend fun consume(purchaseToken: String): Boolean {
        if (!billing.isReady) return false
        val params = ConsumeParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build()
        return Catching {
            withContext(dispatchers.io) { billing.consumePurchase(params) }
        }.getOrNull()?.billingResult?.responseCode == PlayBilling.BillingResponseCode.OK
    }

    private fun productDetailsParams(skus: Set<String>): QueryProductDetailsParams =
        QueryProductDetailsParams.newBuilder()
            .setProductList(
                skus.map { sku ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(sku)
                        .setProductType(PlayBilling.ProductType.INAPP)
                        .build()
                },
            )
            .build()

    private fun BillingResult.toPurchaseResult(purchases: List<Purchase>?): PurchaseResult =
        when (responseCode) {
            PlayBilling.BillingResponseCode.OK -> {
                val purchase = purchases?.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                if (purchase == null) {
                    logger.w { "Purchase OK but no PURCHASED item in the update" }
                    PurchaseResult.Failed("No completed purchase in store response")
                } else {
                    PurchaseResult.Success(purchase.toTransaction())
                }
            }
            PlayBilling.BillingResponseCode.USER_CANCELED -> PurchaseResult.UserCancelled
            PlayBilling.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                val purchase = purchases?.firstOrNull()
                if (purchase != null) {
                    PurchaseResult.AlreadyOwned(purchase.toTransaction())
                } else {
                    PurchaseResult.Failed("Item already owned but no receipt to redeem")
                }
            }
            PlayBilling.BillingResponseCode.SERVICE_DISCONNECTED,
            PlayBilling.BillingResponseCode.SERVICE_UNAVAILABLE,
            -> PurchaseResult.NotConnected
            else -> PurchaseResult.Failed(debugMessage.ifBlank { "Purchase failed (code $responseCode)" })
        }

    private fun Purchase.toTransaction(): PurchaseTransaction = PurchaseTransaction(
        sku = products.firstOrNull().orEmpty(),
        orderId = orderId.orEmpty(),
        purchaseToken = purchaseToken,
        platform = BillingPlatform.Google,
        purchasedAtEpochMs = purchaseTime,
    )

    private fun ProductDetails.toBillingProduct(): BillingProduct? {
        val offer = oneTimePurchaseOfferDetails ?: return null
        return BillingProduct(
            sku = productId,
            displayPrice = offer.formattedPrice,
            currencyCode = offer.priceCurrencyCode,
            priceMicros = offer.priceAmountMicros,
        )
    }
}
