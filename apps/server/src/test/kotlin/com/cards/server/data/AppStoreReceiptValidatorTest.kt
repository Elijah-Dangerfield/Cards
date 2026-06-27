package com.dangerfield.cards.server.data

import com.apple.itunes.storekit.model.JWSTransactionDecodedPayload
import com.apple.itunes.storekit.verification.VerificationException
import com.apple.itunes.storekit.verification.VerificationStatus
import com.dangerfield.cards.server.config.BillingConfig
import com.dangerfield.cards.server.domain.PurchaseReceipt
import com.dangerfield.cards.server.domain.ReceiptValidation
import com.dangerfield.cards.server.domain.Store
import com.dangerfield.cards.server.domain.UserId
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BILL-2 — unit coverage for the Apple StoreKit 2 receipt validator. The JWS
 * signature verification is Apple's library's job (its own test suite covers
 * the crypto); these tests pin the business invariants we layer on top: the
 * dormant-until-configured guard, the SKU match, the user binding via
 * `appAccountToken`, and the revocation check. The decode step is stubbed so
 * the invariants run without a real signed receipt.
 */
class AppStoreReceiptValidatorTest {

    private val userId = UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val otherUser = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @Test
    fun unconfigured_refusesValidation() = runTest {
        val validator = AppStoreReceiptValidator(
            config = unconfigured(),
            rootCertificates = { emptySet() },
            decoder = null,
        )
        val result = validator.validate(receipt())
        assertEquals(ReceiptValidation.Invalid("apple_validator_unconfigured"), result)
    }

    @Test
    fun validReceipt_returnsTransactionIdAsOrderId() = runTest {
        val validator = validatorReturning(
            payload().productId("chips_medium").appAccountToken(userId.value).transactionId("txn-42"),
        )
        val result = validator.validate(receipt())
        assertEquals(ReceiptValidation.Valid("txn-42"), result)
    }

    @Test
    fun forgedJws_rejected_andNeverGrants() = runTest {
        val validator = AppStoreReceiptValidator(
            config = configured(),
            rootCertificates = { emptySet() },
            decoder = { throw VerificationException(VerificationStatus.VERIFICATION_FAILURE) },
        )
        val result = validator.validate(receipt())
        assertEquals(ReceiptValidation.Invalid("apple_jws_invalid"), result)
    }

    @Test
    fun productMismatch_rejected() = runTest {
        val validator = validatorReturning(
            payload().productId("chips_huge").appAccountToken(userId.value).transactionId("txn-1"),
        )
        val result = validator.validate(receipt())
        assertEquals(ReceiptValidation.Invalid("apple_product_mismatch"), result)
    }

    @Test
    fun wrongUserAccountToken_rejected() = runTest {
        val validator = validatorReturning(
            payload().productId("chips_medium").appAccountToken(otherUser).transactionId("txn-1"),
        )
        val result = validator.validate(receipt())
        assertEquals(ReceiptValidation.Invalid("apple_account_mismatch"), result)
    }

    @Test
    fun missingAccountToken_rejected() = runTest {
        val validator = validatorReturning(
            payload().productId("chips_medium").transactionId("txn-1"),
        )
        val result = validator.validate(receipt())
        assertEquals(ReceiptValidation.Invalid("apple_account_mismatch"), result)
    }

    @Test
    fun revokedTransaction_rejected() = runTest {
        val validator = validatorReturning(
            payload()
                .productId("chips_medium")
                .appAccountToken(userId.value)
                .transactionId("txn-1")
                .revocationDate(1_700_000_000_000L),
        )
        val result = validator.validate(receipt())
        assertEquals(ReceiptValidation.Invalid("apple_revoked"), result)
    }

    @Test
    fun appleRootCertificates_bundledAndLoadable() {
        val certs = AppStoreReceiptValidator::class.java
            .getResourceAsStream("/apple-certs/AppleRootCA-G3.cer")
        assertTrue(certs != null, "AppleRootCA-G3.cer must ship in resources for offline JWS verification")
    }

    private fun validatorReturning(payload: JWSTransactionDecodedPayload) =
        AppStoreReceiptValidator(
            config = configured(),
            rootCertificates = { emptySet() },
            decoder = { payload },
        )

    private fun payload() = JWSTransactionDecodedPayload()

    private fun receipt() = PurchaseReceipt(
        store = Store.Apple,
        productId = "chip_pack_medium",
        expectedSku = "chips_medium",
        token = "signed-jws",
        userId = userId,
    )

    private fun configured() = BillingConfig(
        appleBundleId = "com.cards.app",
        appleEnvironment = "Sandbox",
        appleAppAppleId = null,
        googlePackageName = null,
        googleServiceAccountJson = null,
    )

    private fun unconfigured() = BillingConfig(
        appleBundleId = null,
        appleEnvironment = "Sandbox",
        appleAppAppleId = null,
        googlePackageName = null,
        googleServiceAccountJson = null,
    )
}
