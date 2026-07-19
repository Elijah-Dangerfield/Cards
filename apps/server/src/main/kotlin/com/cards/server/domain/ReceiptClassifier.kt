package com.dangerfield.cards.server.domain

/**
 * How a failed redeem should be treated. Every [ReceiptValidation.Invalid] the
 * validators produce collapses to one of these, and each wants a different
 * response from the redeem route and the client (see `docs/wiki/purchases.md`).
 *
 *  - [Transient] — the same receipt would validate on a later attempt (validator
 *    not configured yet, the store's own API was unreachable, a 5xx). Leave the
 *    transaction unfinished so the launch-time redeemer retries it.
 *  - [Mismatch] — the receipt is genuine, paid, signed, and not revoked; it is
 *    just bound to a different one of the user's own accounts. Recoverable: the
 *    client nudges "sign in to claim," and the server can grant to the current
 *    caller on a StoreKit replay (grant-on-replay).
 *  - [Dead] — terminal and unrecoverable: forged / unverifiable signature, wrong
 *    product, refunded or revoked, malformed. Finish it and grant nothing.
 *  - [Wedged] — not produced by [ReceiptClassifier]; it is an escalation state
 *    the client assigns when an otherwise-[Transient] transaction keeps failing
 *    past a retry cap for a reason we didn't anticipate. Finish to unblock, flag
 *    for review, grant goodwill chips.
 */
enum class RedeemDisposition {
    Transient,
    Mismatch,
    Dead,
    Wedged,
}

/**
 * Maps a validator's [ReceiptValidation.Invalid] to the disposition the redeem
 * route acts on. The classification is a pure function of the reason plus the
 * validator's own [ReceiptValidation.Invalid.retryable] flag:
 *
 *  - a reason in [MISMATCH_REASONS] is always [RedeemDisposition.Mismatch] — the
 *    account binding is the only thing that failed;
 *  - otherwise a `retryable` refusal is [RedeemDisposition.Transient];
 *  - everything else is [RedeemDisposition.Dead].
 *
 * The `else -> Dead` fallthrough is deliberate: a future validator reason that
 * nobody remembered to mark `retryable` finishes the transaction rather than
 * looping it forever. A genuinely transient new reason must set `retryable`.
 */
object ReceiptClassifier {

    private val MISMATCH_REASONS = setOf(
        "apple_account_mismatch",
        "google_account_mismatch",
    )

    fun classify(validation: ReceiptValidation.Invalid): RedeemDisposition = when {
        validation.reason in MISMATCH_REASONS -> RedeemDisposition.Mismatch
        validation.retryable -> RedeemDisposition.Transient
        else -> RedeemDisposition.Dead
    }
}
