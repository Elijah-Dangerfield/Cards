import ComposeApp
import Foundation
import StoreKit

/// Swift implementation of the Kotlin `StoreKitCoordinator` protocol
/// (SKIE-exported as `BillingStoreKitCoordinator`). Drives the native StoreKit 2
/// flow — `Product.products(for:)`, `Product.purchase(options:)`,
/// `Transaction.finish()` — and flattens results to the primitive shapes the
/// Kotlin/Native boundary expects.
///
/// Plain callbacks (no async) to match the non-suspend Kotlin interface; each
/// method hops into a `Task` for the StoreKit `async` call and hands the result
/// back. `StoreKitBillingClient` (Kotlin) wraps the callbacks into the suspend
/// `BillingClient` surface.
///
/// `userId` (a Supabase UUID) rides through as `appAccountToken`; StoreKit
/// echoes it on the verified `Transaction.appAccountToken`, which the server
/// pins against the authenticated caller during receipt validation.
class IOSStoreKitCoordinator: NSObject, BillingStoreKitCoordinator {

    private let transactions = UnfinishedTransactions()

    func loadProducts(
        productIds: [String],
        onComplete: @escaping ([BillingStoreKitProduct], String?) -> Void
    ) {
        Task {
            do {
                let products = try await Product.products(for: productIds)
                let mapped = products.map { product in
                    BillingStoreKitProduct(
                        productId: product.id,
                        displayPrice: product.displayPrice,
                        currencyCode: product.priceFormatStyle.currencyCode,
                        priceMicros: Self.priceMicros(product.price)
                    )
                }
                onComplete(mapped, nil)
            } catch {
                onComplete([], error.localizedDescription)
            }
        }
    }

    func purchase(
        productId: String,
        appAccountToken: String,
        onComplete: @escaping (BillingStoreKitPurchaseResult) -> Void
    ) {
        Task {
            do {
                let products = try await Product.products(for: [productId])
                guard let product = products.first else {
                    onComplete(Self.failed("Unknown product: \(productId)"))
                    return
                }

                // Fail BEFORE payment: a purchase without the appAccountToken
                // would charge the user and then be rejected server-side
                // (apple_account_mismatch) during receipt validation.
                guard let token = UUID(uuidString: appAccountToken) else {
                    onComplete(Self.failed("appAccountToken is not a UUID: \(appAccountToken)"))
                    return
                }
                let options: Set<Product.PurchaseOption> = [.appAccountToken(token)]

                let result = try await product.purchase(options: options)
                onComplete(await map(result, productId: productId))
            } catch {
                onComplete(Self.failed(error.localizedDescription))
            }
        }
    }

    func finishTransaction(
        jwsRepresentation: String,
        onComplete: @escaping (KotlinBoolean) -> Void
    ) {
        Task {
            if let transaction = await transactions.take(jwsRepresentation) {
                await transaction.finish()
            }
            // Missing handle → already finished (or never seen); report done so
            // the use case doesn't strand a granted purchase.
            onComplete(KotlinBoolean(bool: true))
        }
    }

    /// Verified transactions StoreKit still holds unfinished — the
    /// paid-but-uncredited recovery source (BILL-7). A failed server redeem
    /// leaves the transaction unfinished, and Apple replays it here on every
    /// launch until `finish()` runs. Handles are retained in the same cache
    /// `purchase` uses so a later `finishTransaction` can complete them.
    func loadUnfinishedTransactions(
        onComplete: @escaping ([BillingStoreKitPurchaseResult]) -> Void
    ) {
        Task {
            var results: [BillingStoreKitPurchaseResult] = []
            for await verification in Transaction.unfinished {
                guard case .verified(let transaction) = verification else { continue }
                let jws = verification.jwsRepresentation
                await transactions.store(jws, transaction)
                results.append(
                    BillingStoreKitPurchaseResult(
                        status: .success,
                        productId: transaction.productID,
                        transactionId: String(transaction.id),
                        jwsRepresentation: jws,
                        purchasedAtEpochMs: Int64(transaction.purchaseDate.timeIntervalSince1970 * 1000),
                        displayPrice: nil,
                        errorMessage: nil
                    )
                )
            }
            onComplete(results)
        }
    }

    private func map(
        _ result: Product.PurchaseResult,
        productId: String
    ) async -> BillingStoreKitPurchaseResult {
        switch result {
        case .success(let verification):
            switch verification {
            case .verified(let transaction):
                let jws = verification.jwsRepresentation
                await transactions.store(jws, transaction)
                return BillingStoreKitPurchaseResult(
                    status: .success,
                    productId: transaction.productID,
                    transactionId: String(transaction.id),
                    jwsRepresentation: jws,
                    purchasedAtEpochMs: Int64(transaction.purchaseDate.timeIntervalSince1970 * 1000),
                    displayPrice: nil,
                    errorMessage: nil
                )
            case .unverified(_, let error):
                return Self.failed("Unverified transaction: \(error.localizedDescription)")
            }
        case .userCancelled:
            return BillingStoreKitPurchaseResult(
                status: .userCancelled,
                productId: productId,
                transactionId: nil,
                jwsRepresentation: nil,
                purchasedAtEpochMs: 0,
                displayPrice: nil,
                errorMessage: nil
            )
        case .pending:
            return BillingStoreKitPurchaseResult(
                status: .pending,
                productId: productId,
                transactionId: nil,
                jwsRepresentation: nil,
                purchasedAtEpochMs: 0,
                displayPrice: nil,
                errorMessage: "Purchase is pending approval"
            )
        @unknown default:
            return Self.failed("Unknown StoreKit purchase result")
        }
    }

    private static func failed(_ message: String) -> BillingStoreKitPurchaseResult {
        BillingStoreKitPurchaseResult(
            status: .failed,
            productId: nil,
            transactionId: nil,
            jwsRepresentation: nil,
            purchasedAtEpochMs: 0,
            displayPrice: nil,
            errorMessage: message
        )
    }

    /// Decimal price → integer micros (price * 1_000_000), matching the
    /// `priceMicros` contract on the Kotlin side.
    private static func priceMicros(_ price: Decimal) -> Int64 {
        let micros = price * Decimal(1_000_000)
        return NSDecimalNumber(decimal: micros).int64Value
    }
}

/// Verified-but-unfinished consumable transactions, keyed by their JWS — the
/// same identifier that rides the Kotlin `purchaseToken`. StoreKit replays an
/// unfinished consumable until `finish()` is called, so we hold the live
/// `Transaction` until the use case calls `finishTransaction` (after the server
/// grants the chips). An actor so the cache is safe across the concurrent
/// purchase / finish tasks.
private actor UnfinishedTransactions {
    private var byJws: [String: Transaction] = [:]

    func store(_ jws: String, _ transaction: Transaction) {
        byJws[jws] = transaction
    }

    func take(_ jws: String) -> Transaction? {
        byJws.removeValue(forKey: jws)
    }
}
