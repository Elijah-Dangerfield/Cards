package com.dangerfield.cards.server.domain

/**
 * Records a redeemed store purchase and grants the chips, atomically and
 * idempotently.
 *
 * The single seam behind `POST /v1/billing/redeem`: insert the
 * `billing_transactions` row and apply the wallet credit in one
 * transaction, keyed on `(store, order_id)` so a retried redemption never
 * double-grants. Receipt validation happens in the route (via
 * [ReceiptValidator]) before this is called — this layer only owns the
 * grant-once invariant.
 */
interface BillingRepository {

    /**
     * Grant [grantedChips] to [userId] for the validated store transaction
     * [orderId], recording it under [store] with the [environment] that
     * verified the receipt (sandbox mints are free chips, not revenue — the
     * distinction flows into both the transaction row and the wallet-ledger
     * reason). Idempotent on `(store, orderId)`: a repeat call (client retry,
     * racing request) is a no-op that returns the current balance with
     * [RedeemResult.AlreadyRedeemed].
     *
     * [relaxedAccountBinding] marks a grant-on-replay: the receipt's account
     * token belonged to a different one of the user's accounts and the strict
     * BILL-11 binding was relaxed to credit the current caller (only ever for a
     * StoreKit-replayed transaction — see `docs/wiki/purchases.md`). It shifts
     * the wallet-ledger reason to a distinct `.replay` variant so relaxed grants
     * are queryable, while staying under the `iap.%` prefix that marks real-money
     * spend (so a paying user is never swept as an orphan). The grant stays
     * idempotent on the transaction id regardless.
     */
    suspend fun redeem(
        userId: UserId,
        store: String,
        orderId: String,
        productId: String,
        grantedChips: Long,
        environment: PurchaseEnvironment,
        relaxedAccountBinding: Boolean = false,
    ): RedeemResult
}

/**
 * Outcome of [BillingRepository.redeem]. Both success shapes carry the
 * authoritative post-grant [balance] so the endpoint can return it without
 * a second read.
 */
sealed interface RedeemResult {
    val balance: Long

    /** The transaction was new: chips were granted, the row was written. */
    data class Granted(override val balance: Long) : RedeemResult

    /**
     * This `(store, orderId)` was already redeemed — a no-op replay. The
     * balance is the user's current balance, unchanged by this call.
     */
    data class AlreadyRedeemed(override val balance: Long) : RedeemResult
}
