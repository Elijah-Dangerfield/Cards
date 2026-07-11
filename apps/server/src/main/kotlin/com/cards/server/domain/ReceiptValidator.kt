package com.dangerfield.cards.server.domain

/**
 * Validates a store purchase receipt before the server credits any chips.
 *
 * This is the trust boundary for IAP: the client sends what the platform
 * store handed it (a StoreKit 2 signed transaction JWS, or a Play purchase
 * token), and the validator confirms it is a genuine, paid-for purchase of
 * the claimed product by the claimed user. Only on a [Valid] result does
 * `POST /v1/billing/redeem` grant chips.
 *
 * BILL-1 ships the seam plus a [FakeReceiptValidator] for tests and dev.
 * The real platform impls — Apple App Store Server API + Google Play
 * Developer API — are BILL-2; both stay dormant (refusing validation) until
 * their credentials are configured, so an unconfigured server can never
 * accept a forged receipt by default.
 */
interface ReceiptValidator {

    /**
     * Validate [request] against the platform store.
     *
     * Implementations MUST verify, at minimum, that the receipt is a
     * genuine paid purchase of [PurchaseReceipt.productId] AND that it is
     * bound to [PurchaseReceipt.userId] — the account token echoed back by
     * the store (StoreKit `appAccountToken` / Play
     * `obfuscatedExternalAccountId`), NOT the store order id. Pinning to the
     * order id would let one user redeem another user's receipt.
     */
    suspend fun validate(request: PurchaseReceipt): ReceiptValidation
}

/**
 * Platform marketplace a receipt came from. The wire value is lowercase
 * (`"apple"` / `"google"`) so it doubles as the `store` column in
 * `billing_transactions`.
 */
enum class Store(val wire: String) {
    Apple("apple"),
    Google("google"),
    ;

    companion object {
        fun fromWire(value: String): Store? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Everything the validator needs to vouch for a purchase. [token] is the
 * opaque platform proof — a StoreKit 2 signed-transaction JWS for Apple, a
 * purchase token for Google. [userId] is the authenticated caller; a
 * genuine receipt must carry a matching account token.
 *
 * [productId] is our catalog product id (the redeem boundary), while
 * [expectedSku] is the platform store SKU that the receipt actually carries
 * (`Product.ChipPack.store.{ios,android}.sku`). The two differ — the route
 * resolves the catalog product and hands the validator the SKU it must see
 * in the decoded transaction, so a receipt for a different product cannot be
 * redeemed against this one.
 */
data class PurchaseReceipt(
    val store: Store,
    val productId: String,
    val expectedSku: String,
    val token: String,
    val userId: UserId,
)

/**
 * Which store environment verified a purchase. TestFlight (and Play test
 * tracks) always transact against the platform's sandbox — the tester pays
 * nothing — so a sandbox mint is not revenue and must stay distinguishable
 * from real money everywhere downstream: the `billing_transactions.environment`
 * column, the wallet ledger (`iap.<product>` vs `iap_sandbox.<product>`
 * reasons), and the economy dashboards that sum supply and revenue.
 */
enum class PurchaseEnvironment(val wire: String) {
    Production("production"),
    Sandbox("sandbox"),
}

/**
 * Outcome of [ReceiptValidator.validate].
 *
 *  - [Valid] — the receipt is genuine and bound to the user. Carries the
 *    platform's stable transaction id, which becomes the
 *    `(store, order_id)` idempotency key for the grant, and the
 *    [PurchaseEnvironment] that verified it.
 *  - [Invalid] — the receipt was rejected (forged, wrong product, wrong
 *    user, refunded, or the validator is unconfigured). [reason] is a short
 *    machine-ish code for logs; never credit on this result.
 */
sealed interface ReceiptValidation {
    data class Valid(val orderId: String, val environment: PurchaseEnvironment) : ReceiptValidation
    data class Invalid(val reason: String) : ReceiptValidation
}
