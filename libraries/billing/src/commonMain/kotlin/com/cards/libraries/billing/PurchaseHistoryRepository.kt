package com.dangerfield.cards.libraries.billing

/**
 * Reads the signed-in user's purchase history from the server
 * (`GET /v1/billing/history`) and enriches each row with the pack's display
 * details from the local catalog. Backs the in-app purchase-history screen,
 * which the App Store's buried per-account list can't (it isn't app-specific).
 */
interface PurchaseHistoryRepository {

    /**
     * The caller's purchases, newest first. [PurchaseHistoryOutcome.Loaded] on a
     * successful fetch (possibly empty); [PurchaseHistoryOutcome.Unavailable]
     * when the server couldn't be reached, so the screen can show a retry rather
     * than an empty state that reads as "you've never bought anything."
     */
    suspend fun history(): PurchaseHistoryOutcome
}

sealed interface PurchaseHistoryOutcome {
    data class Loaded(val items: List<PurchaseHistoryItem>) : PurchaseHistoryOutcome
    data object Unavailable : PurchaseHistoryOutcome
}

/**
 * One purchase for the history list. [title], [iconEmoji], and [chips] are
 * resolved from the local catalog by product id; when the pack has been delisted
 * they fall back to a generic label so an old purchase still renders.
 */
data class PurchaseHistoryItem(
    val transactionId: String,
    val productId: String,
    val title: String,
    val iconEmoji: String,
    val chips: Long,
    val status: PurchaseStatus,
    val dateEpochMs: Long,
)

/** The coarse state the history screen shows for a purchase. */
enum class PurchaseStatus {
    /** Chips landed. */
    Added,

    /** We're still working on it (waiting on a sign-in, a retry, or review). */
    Pending,

    /** Refunded by the store, so no chips were added. */
    Refunded;

    companion object {
        fun fromWire(value: String): PurchaseStatus = when (value) {
            "added" -> Added
            "refunded" -> Refunded
            else -> Pending
        }
    }
}
