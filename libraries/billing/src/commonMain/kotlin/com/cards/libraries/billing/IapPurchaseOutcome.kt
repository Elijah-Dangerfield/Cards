package com.dangerfield.cards.libraries.billing

/**
 * Result of a chip-pack IAP purchase, returned by [PurchaseChipPackUseCase].
 *
 * Callers dispatch on this to render the right user feedback — the use case
 * doesn't decide on copy or animation. Cancellation is silent (no toast);
 * failure is a single "couldn't complete" line; success is a chip-pile
 * celebration with the granted amount.
 *
 * Lives in the billing api module (not the shop feature) so any caller that
 * wants to drive a real-money chip purchase — the shop grid, the in-game
 * quick-buy sheet — shares one outcome type.
 */
sealed interface IapPurchaseOutcome {
    data class Success(val grantedChips: Long) : IapPurchaseOutcome

    /** Store reports already-owned. We re-credit defensively. */
    data class AlreadyOwned(val grantedChips: Long) : IapPurchaseOutcome

    /** User backed out of the store sheet. Silent. */
    data object Cancelled : IapPurchaseOutcome

    /** Platform billing connection isn't established. Surface "store unavailable". */
    data object StoreUnavailable : IapPurchaseOutcome

    /** No signed-in user. Should be impossible from a real screen, but defend. */
    data object NotSignedIn : IapPurchaseOutcome

    /**
     * Anonymous user tapped buy on a real-money pack. Real-money IAP is gated
     * behind account claim — the caller routes to the claim flow before any
     * purchase rather than yanking them into onboarding unprompted.
     */
    data object ClaimAccountRequired : IapPurchaseOutcome

    /** Generic transient error. [reason] is store-provided and not localized. */
    data class Failed(val reason: String) : IapPurchaseOutcome
}
