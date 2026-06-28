package com.dangerfield.cards.libraries.billing

/**
 * Client seam onto the server-authoritative redemption endpoint
 * (`POST /v1/billing/redeem`, BILL-1). Takes a finished platform
 * [PurchaseTransaction] and asks the server to validate the receipt and grant
 * the chips; the client never claims the chip amount or the post-grant balance.
 *
 * Used by [PurchaseChipPackUseCase] only when [RealPurchasesEnabled] is on. The
 * impl is a pure I/O leaf — it owns the URL + DTOs and nothing else.
 */
interface BillingRepository {

    /**
     * Redeem a finished purchase. [catalogProductId] is the server-catalog
     * product id (`Product.ChipPack.id`, e.g. `chip_pack_medium`) the server
     * resolves `grantsChips` from and derives the per-store SKU to verify the
     * receipt against — NOT the platform store SKU on the transaction, which
     * differs per platform. The server validates the receipt, resolves the
     * product to its server-side `grantsChips`, and grants idempotently on the
     * store transaction id.
     */
    suspend fun redeem(catalogProductId: String, transaction: PurchaseTransaction): RedeemOutcome
}

/** Outcome of a [BillingRepository.redeem] round-trip. */
sealed interface RedeemOutcome {
    /**
     * Server granted (or idempotently re-confirmed) the purchase. [balance] is
     * the authoritative post-grant chip balance the client reflects directly;
     * [grantedChips] is the server-side pack amount; [alreadyRedeemed] is true on
     * an idempotent replay so the caller can suppress a duplicate celebration.
     */
    data class Granted(
        val balance: Long,
        val grantedChips: Long,
        val alreadyRedeemed: Boolean,
    ) : RedeemOutcome

    /**
     * The server rejected the receipt (forged / unverifiable) or the product id
     * was unknown. Nothing was granted; the caller must not credit locally.
     */
    data object Rejected : RedeemOutcome

    /**
     * The redeem call couldn't reach the server (network / 5xx / auth). The
     * purchase stands at the store but isn't credited yet — a later sync or
     * retry recovers it. The caller must not credit locally.
     */
    data object Unavailable : RedeemOutcome
}
