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
}
