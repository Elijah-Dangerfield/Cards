package com.dangerfield.cards.libraries.billing

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Bridge to the native StoreKit 2 flow, satisfied by a Swift class injected
 * from `iOSApp.swift` (SKIE-exported as `BillingStoreKitCoordinator`). Android
 * binds a no-op
 * ([com.dangerfield.cards.libraries.billing.impl.AndroidStoreKitCoordinator])
 * because StoreKit is iOS-only.
 *
 * This mirrors [com.dangerfield.cards.libraries.identity.auth.AppleSignInCoordinator]:
 * StoreKit 2's `Product.purchase()` / `Transaction` APIs are Swift-only `async`,
 * so the impl has to live in Swift. The Kotlin/Native interface is therefore a
 * **plain callback, not `suspend`**, with plain parameter types — a Swift class
 * satisfies it as a trivial protocol conformance with no `suspend`/`Throwable`
 * bridging across the boundary. The suspend `StoreKitBillingClient` wraps these
 * callbacks back into the [BillingClient] coroutine surface via [awaitProducts]
 * / [awaitPurchase] / [awaitFinish].
 *
 * The coordinator deals in primitive/serializable shapes ([StoreKitProduct],
 * [StoreKitPurchaseResult]) rather than the api's [BillingProduct] /
 * [PurchaseResult] so the Swift side never has to construct a Kotlin sealed
 * hierarchy — [StoreKitBillingClient] maps them.
 */
interface StoreKitCoordinator {

    /**
     * Look up [productIds] in the App Store (StoreKit `Product.products(for:)`).
     * Reports one [StoreKitProduct] per id the store recognizes; ids the store
     * doesn't know are simply absent. [errorMessage] is non-null only on a hard
     * lookup failure (offline, StoreKit unavailable).
     */
    fun loadProducts(
        productIds: List<String>,
        onComplete: (products: List<StoreKitProduct>, errorMessage: String?) -> Unit,
    )

    /**
     * Run the native purchase sheet for [productId] (StoreKit
     * `Product.purchase(options:)` with `.appAccountToken(appAccountToken)`).
     * [appAccountToken] is the player's Supabase user id — a UUID — which
     * StoreKit echoes back on the verified `Transaction.appAccountToken` so the
     * server can pin the receipt to the caller.
     */
    fun purchase(
        productId: String,
        appAccountToken: String,
        onComplete: (result: StoreKitPurchaseResult) -> Unit,
    )

    /**
     * Finish a consumable transaction (StoreKit `Transaction.finish()`). Keyed
     * by the [jwsRepresentation] the purchase carried — the same value that
     * rides [BillingClient.consume] as the `purchaseToken`, so the use case
     * passes one identifier end to end. The coordinator retains the unfinished
     * `Transaction` (Apple keeps replaying it until finished) and looks it up by
     * its JWS. Reports `true` once finished. Chip packs are consumables, so this
     * is the iOS arm of [BillingClient.consume].
     */
    fun finishTransaction(
        jwsRepresentation: String,
        onComplete: (finished: Boolean) -> Unit,
    )
}

/** A StoreKit `Product` flattened to primitives for the Kotlin/Native boundary. */
data class StoreKitProduct(
    val productId: String,
    val displayPrice: String,
    val currencyCode: String,
    val priceMicros: Long,
)

/**
 * Outcome of [StoreKitCoordinator.purchase], flattened so the Swift side never
 * builds a Kotlin sealed type. [StoreKitBillingClient] maps it to [PurchaseResult].
 *
 * On `success` / `alreadyPurchased`, the transaction fields carry the verified
 * receipt; [transactionId] doubles as the StoreKit transaction id passed to
 * [StoreKitCoordinator.finishTransaction].
 */
data class StoreKitPurchaseResult(
    val status: StoreKitPurchaseStatus,
    val productId: String? = null,
    val transactionId: String? = null,
    /** Base64 signed JWS payload (`VerificationResult.jwsRepresentation`) the server validates. */
    val jwsRepresentation: String? = null,
    val purchasedAtEpochMs: Long = 0L,
    val displayPrice: String? = null,
    val errorMessage: String? = null,
)

enum class StoreKitPurchaseStatus {
    Success,
    AlreadyPurchased,
    UserCancelled,
    Pending,
    Failed,
}

/** Maps a flattened StoreKit product onto the platform-agnostic [BillingProduct]. */
fun StoreKitProduct.toBillingProduct(): BillingProduct = BillingProduct(
    sku = productId,
    displayPrice = displayPrice,
    currencyCode = currencyCode,
    priceMicros = priceMicros,
)

/**
 * Maps a flattened StoreKit purchase onto the [BillingResult][PurchaseResult]
 * contract. Success / already-purchased require a verified transaction (product
 * id + StoreKit transaction id + signed JWS); a status that claims success
 * without one is downgraded to [PurchaseResult.Failed] rather than crediting an
 * unverifiable purchase.
 *
 * The signed JWS becomes [PurchaseTransaction.purchaseToken] — the server
 * validates it as a signed transaction and it doubles as the `consume()` /
 * `finishTransaction` key. The StoreKit transaction id becomes
 * [PurchaseTransaction.orderId], the stable idempotency key.
 */
fun StoreKitPurchaseResult.toPurchaseResult(): PurchaseResult = when (status) {
    StoreKitPurchaseStatus.Success -> toTransaction()
        ?.let { PurchaseResult.Success(it) }
        ?: PurchaseResult.Failed("StoreKit success without a verified transaction")
    StoreKitPurchaseStatus.AlreadyPurchased -> toTransaction()
        ?.let { PurchaseResult.AlreadyOwned(it) }
        ?: PurchaseResult.Failed("StoreKit reported already purchased without a transaction")
    StoreKitPurchaseStatus.UserCancelled -> PurchaseResult.UserCancelled
    StoreKitPurchaseStatus.Pending -> PurchaseResult.Failed(errorMessage ?: "Purchase is pending approval")
    StoreKitPurchaseStatus.Failed -> PurchaseResult.Failed(errorMessage ?: "StoreKit purchase failed")
}

private fun StoreKitPurchaseResult.toTransaction(): PurchaseTransaction? {
    val productId = productId ?: return null
    val transactionId = transactionId ?: return null
    val jws = jwsRepresentation ?: return null
    return PurchaseTransaction(
        sku = productId,
        orderId = transactionId,
        purchaseToken = jws,
        platform = BillingPlatform.Apple,
        purchasedAtEpochMs = purchasedAtEpochMs,
        displayPrice = displayPrice,
    )
}

suspend fun StoreKitCoordinator.awaitProducts(
    productIds: List<String>,
): Result<List<StoreKitProduct>> = suspendCancellableCoroutine { continuation ->
    loadProducts(productIds) { products, errorMessage ->
        if (errorMessage != null) {
            continuation.resume(Result.failure(StoreKitException(errorMessage)))
        } else {
            continuation.resume(Result.success(products))
        }
    }
}

suspend fun StoreKitCoordinator.awaitPurchase(
    productId: String,
    appAccountToken: String,
): StoreKitPurchaseResult = suspendCancellableCoroutine { continuation ->
    purchase(productId, appAccountToken) { result -> continuation.resume(result) }
}

suspend fun StoreKitCoordinator.awaitFinish(
    jwsRepresentation: String,
): Boolean = suspendCancellableCoroutine { continuation ->
    finishTransaction(jwsRepresentation) { finished -> continuation.resume(finished) }
}

/** Raised by [awaitProducts] when the native product lookup reports a hard failure. */
class StoreKitException(message: String) : Throwable(message)
