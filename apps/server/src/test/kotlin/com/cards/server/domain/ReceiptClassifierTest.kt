package com.dangerfield.cards.server.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the reason -> disposition mapping every validator feeds into
 * `POST /v1/billing/redeem`. The disposition, not the raw reason, decides the
 * HTTP status and what the client does next (see `docs/wiki/purchases.md`), so
 * every reason the Apple and Google validators can emit is covered here.
 */
class ReceiptClassifierTest {

    @Test
    fun accountMismatchReasons_areMismatch() {
        assertEquals(RedeemDisposition.Mismatch, classify("apple_account_mismatch"))
        assertEquals(RedeemDisposition.Mismatch, classify("google_account_mismatch"))
    }

    @Test
    fun retryableReasons_areTransient() {
        assertEquals(RedeemDisposition.Transient, classify("apple_validator_unconfigured", retryable = true))
        assertEquals(RedeemDisposition.Transient, classify("google_validator_unconfigured", retryable = true))
        assertEquals(RedeemDisposition.Transient, classify("google_lookup_failed", retryable = true))
    }

    @Test
    fun terminalNonMismatchReasons_areDead() {
        listOf(
            "apple_jws_invalid",
            "apple_product_mismatch",
            "apple_revoked",
            "apple_missing_transaction_id",
            "google_not_purchased",
            "google_missing_order_id",
            "empty_token",
        ).forEach { reason ->
            assertEquals(RedeemDisposition.Dead, classify(reason), "reason=$reason")
        }
    }

    @Test
    fun accountMismatch_isMismatch_evenIfMarkedRetryable() {
        // The account binding is the only failure; a mismatch is recoverable,
        // never transient — even if a future validator flips the retry flag.
        assertEquals(RedeemDisposition.Mismatch, classify("apple_account_mismatch", retryable = true))
    }

    @Test
    fun unknownNonRetryableReason_fallsThroughToDead() {
        // A future reason nobody remembered to mark retryable finishes the
        // transaction rather than looping it forever.
        assertEquals(RedeemDisposition.Dead, classify("some_new_reason_we_forgot"))
    }

    private fun classify(reason: String, retryable: Boolean = false): RedeemDisposition =
        ReceiptClassifier.classify(ReceiptValidation.Invalid(reason, retryable = retryable))
}
