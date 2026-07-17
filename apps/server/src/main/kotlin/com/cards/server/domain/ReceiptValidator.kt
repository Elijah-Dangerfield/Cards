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
     * bound to the caller — the account token echoed back by the store
     * (StoreKit `appAccountToken` / Play `obfuscatedExternalAccountId`) must
     * equal [PurchaseReceipt.userId] or one of [PurchaseReceipt.accountLineage]
     * (the caller's account-upgrade lineage), NOT the store order id. Pinning
     * to the order id would let one user redeem another user's receipt.
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
 *
 * [accountLineage] is the caller's account-upgrade lineage: the prior user
 * ids that owned this install before the current [userId] (AUTH-19 anon →
 * anon / anon → claimed on the same device, resolved server-side by shared
 * `install_id`). A StoreKit transaction stamps its `appAccountToken` at
 * purchase time with whoever the user was then, so a pack bought under a
 * prior identity carries that old id forever. The validator accepts a
 * receipt whose account token is [userId] OR any id in [accountLineage] —
 * that's what lets a purchase made before an account upgrade still redeem
 * (BILL-11). Empty by default: a caller that doesn't resolve a lineage keeps
 * the strict "token must equal the caller" binding.
 */
data class PurchaseReceipt(
    val store: Store,
    val productId: String,
    val expectedSku: String,
    val token: String,
    val userId: UserId,
    val accountLineage: Set<UserId> = emptySet(),
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
 *    machine-ish code for logs; never credit on this result. [retryable]
 *    distinguishes a transient refusal (validator not yet configured, the
 *    store's own API was unreachable) — where the same receipt would validate
 *    on a later attempt — from a terminal one (forged, mismatched, revoked)
 *    that will never validate. The redeem route turns a terminal Invalid into
 *    a 400 the client acts on by finishing the stuck transaction, and a
 *    retryable Invalid into a 503 the client leaves unfinished to replay later
 *    (BILL-13). Terminal is the safe default: only mark a rejection retryable
 *    when finishing the transaction would strand a genuinely paid purchase.
 */
sealed interface ReceiptValidation {
    data class Valid(val orderId: String, val environment: PurchaseEnvironment) : ReceiptValidation
    data class Invalid(val reason: String, val retryable: Boolean = false) : ReceiptValidation
}
