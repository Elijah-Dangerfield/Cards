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
     *
     * [replayed] must be true only when draining a store-replayed, unfinished
     * transaction (the outstanding-purchase path), and false for an interactive
     * buy. The server uses it to gate grant-on-replay: recovering a
     * paid-but-wrong-account receipt to the current caller is only ever
     * considered for a StoreKit-replayed transaction (`docs/wiki/purchases.md`).
     */
    suspend fun redeem(
        catalogProductId: String,
        transaction: PurchaseTransaction,
        replayed: Boolean = false,
    ): RedeemOutcome
}

/**
 * Outcome of a [BillingRepository.redeem] round-trip, mirroring the server's
 * redeem disposition (see `docs/wiki/purchases.md`). The disposition decides
 * whether the caller finishes the transaction or leaves it open to retry, and
 * whether recovery (sign-in-to-claim / grant-on-replay) is worth attempting.
 */
sealed interface RedeemOutcome {
    /**
     * Server granted (or idempotently re-confirmed) the purchase. [balance] is
     * the authoritative post-grant chip balance the client reflects directly;
     * [grantedChips] is the server-side pack amount; [alreadyRedeemed] is true on
     * an idempotent replay so the caller can suppress a duplicate celebration.
     * [goodwill] is true when the grant was a wedged-purchase escalation, so the
     * caller can show the "we hit a snag, we made it right" message. Terminal:
     * the caller finishes the transaction.
     */
    data class Granted(
        val balance: Long,
        val grantedChips: Long,
        val alreadyRedeemed: Boolean,
        val goodwill: Boolean = false,
    ) : RedeemOutcome

    /**
     * The receipt is genuine, paid, signed, and not revoked, but its account
     * token is bound to a different one of the user's own accounts (the common
     * reinstall-before-sign-in case). Nothing was granted. Recoverable: the
     * caller can nudge "sign in to claim your purchase," and the server can
     * grant to the current caller on a StoreKit replay (grant-on-replay).
     */
    data object Mismatch : RedeemOutcome

    /**
     * A StoreKit-replayed receipt that would grant-on-replay, but the current
     * caller is anonymous — so the server nudged them to sign in first, because
     * re-login makes the receipt match its own account cleanly and lands the
     * chips on the durable claimed account. Nothing was granted. The caller must
     * leave the transaction unfinished so the next drain after sign-in resolves
     * it, and surface a "sign in to claim your purchase" message.
     */
    data object ClaimSignIn : RedeemOutcome

    /**
     * The server terminally rejected the receipt: it will never validate —
     * forged / unverifiable, wrong product, or a refund/revocation. Nothing was
     * granted, and retrying is pointless. The caller MUST finish (consume) the
     * transaction: a consumable left unfinished replays every launch AND shadows
     * every new purchase of the same SKU, which is what turned one unredeemable
     * receipt into "every purchase fails" on a fresh install (BILL-13).
     */
    data object Dead : RedeemOutcome

    /**
     * The redeem couldn't be resolved yet: the server was unreachable (network /
     * 5xx / auth), receipt validation was temporarily unavailable (503), or a
     * 4xx we don't finish on (unknown product / catalog drift). The purchase
     * stands at the store but isn't credited yet — a later sync or retry
     * recovers it. The caller must not credit locally, and must leave the
     * transaction unfinished so the launch-time redeemer can drain it.
     */
    data object Transient : RedeemOutcome
}
