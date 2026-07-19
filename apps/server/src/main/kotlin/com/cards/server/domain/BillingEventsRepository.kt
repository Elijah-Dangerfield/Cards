package com.dangerfield.cards.server.domain

/**
 * Records the disposition of every redeem attempt that has a known store
 * transaction id — the source of truth behind support lookups and the Grafana
 * billing-health panel (see `docs/wiki/purchases.md`). One evolving row per
 * `(store, transactionId)`: each attempt bumps the count and rewrites the
 * latest caller, reason, and action, so a row tells a purchase's whole journey.
 *
 * This is the disposition log, NOT the money ledger. The grant + idempotency
 * live in `billing_transactions` / the wallet ledger; a write here is
 * best-effort audit and must never block or fail a grant.
 */
interface BillingEventsRepository {

    /** Upsert the attempt for `(store, transactionId)`, incrementing its count. */
    suspend fun record(event: BillingEventAttempt)

    /**
     * How many times `(store, transactionId)` has already been recorded, 0 if
     * never. The wedged escalation reads this to decide when a stuck purchase
     * has been re-attempted past the retry cap.
     */
    suspend fun attemptCountFor(store: String, transactionId: String): Int

    /**
     * The caller's purchase attempts, newest first, capped at [limit]. Backs the
     * in-app purchase-history screen; each row carries the product, the latest
     * disposition, and when it was last touched.
     */
    suspend fun historyFor(userId: UserId, limit: Int = 50): List<BillingEventRecord>
}

/** A billing-events row as read back for the purchase-history screen. */
@kotlin.OptIn(kotlin.time.ExperimentalTime::class)
data class BillingEventRecord(
    val store: String,
    val transactionId: String,
    val productId: String,
    val action: BillingEventAction,
    val reason: String?,
    val updatedAt: kotlin.time.Instant,
)

/**
 * One redeem attempt against an identified store transaction. [receiptOwner] is
 * the account the receipt's token names (present for a mismatch, null otherwise);
 * [callerUser] is who made this attempt. [reason] is the validator reason or a
 * short disposition note; [action] is where this attempt ended up.
 */
data class BillingEventAttempt(
    val store: String,
    val transactionId: String,
    val callerUser: UserId,
    val receiptOwner: UserId?,
    val productId: String,
    val reason: String?,
    val action: BillingEventAction,
)

/**
 * Where a redeem attempt ended up. Drives the billing-health panel's
 * grant / mismatch / refund / escalation slices.
 */
enum class BillingEventAction(val wire: String) {
    /** Granted to the caller against a matching account binding. */
    Granted("granted"),

    /** Granted to the caller with the account binding relaxed (grant-on-replay). */
    GrantedOnReplay("granted_on_replay"),

    /** Genuine, paid, wrong account, not relaxed (interactive buy, or rate-limited). */
    Mismatch("mismatch"),

    /** Anonymous caller nudged to sign in to claim; left unfinished. */
    ClaimSignIn("claim_sign_in"),

    /** Terminal and unrecoverable — refunded / revoked. Client finishes it. */
    FinishedDead("finished_dead"),

    /** Wedged past the retry cap: finished to unblock, flagged for review. */
    Escalated("escalated"),
}
