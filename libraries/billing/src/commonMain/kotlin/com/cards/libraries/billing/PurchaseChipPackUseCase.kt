package com.dangerfield.cards.libraries.billing

import com.dangerfield.cards.libraries.products.Product

/**
 * Drives a real-money chip-pack purchase to completion: launches the platform
 * store sheet, credits chips on success, acknowledges the receipt, and reports
 * an [IapPurchaseOutcome].
 *
 * Extracted from the shop so the in-game quick-buy flow (busted-player rebuy)
 * shares the exact same purchase + credit + anonymous-gating logic. Both the
 * shop grid and the play screen call this; neither owns the billing details.
 */
interface PurchaseChipPackUseCase {
    suspend operator fun invoke(pack: Product.ChipPack): IapPurchaseOutcome

    /**
     * Re-run the redeem → grant → finish path for every purchase the store
     * still holds unfinished — the paid-but-uncredited recovery (BILL-7). A
     * failed redeem leaves the transaction unfinished at the store; this
     * drains them once a session is available. Idempotent server-side on the
     * store transaction id, so a replay never double-credits. Driven on launch
     * by `OutstandingPurchaseRedeemer`; safe to call anytime. Default no-op so
     * test fakes that don't drive recovery needn't implement it.
     */
    suspend fun redeemOutstanding() {}
}
