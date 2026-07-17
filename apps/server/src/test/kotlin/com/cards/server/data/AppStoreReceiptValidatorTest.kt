package com.dangerfield.cards.server.data

import com.apple.itunes.storekit.model.Environment
import com.apple.itunes.storekit.model.JWSTransactionDecodedPayload
import com.apple.itunes.storekit.verification.VerificationException
import com.apple.itunes.storekit.verification.VerificationStatus
import com.dangerfield.cards.server.config.BillingConfig
import com.dangerfield.cards.server.domain.PurchaseEnvironment
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
    private val priorIdentity = UUID.fromString("52f3f9c1-1a94-4640-b24c-560a9b7534eb")

    @Test
    fun unconfigured_refusesValidation() = runTest {
        val validator = AppStoreReceiptValidator(
            config = unconfigured(),
            rootCertificates = { emptySet() },
            decoder = null,
        )
        val result = validator.validate(receipt())
        // Retryable: an unconfigured validator must not make the client finish
        // (and strand) the transaction — it validates once the bundle id is set.
        assertEquals(ReceiptValidation.Invalid("apple_validator_unconfigured", retryable = true), result)
    }

    @Test
    fun productionEnvironment_withoutAppAppleId_degradesInsteadOfThrowing() = runTest {
        // BILL-7: Apple's SignedDataVerifier cannot be built for PRODUCTION
        // without the numeric appAppleId. Before the per-environment guard,
        // the lazily-thrown IllegalArgumentException escaped the first redeem
        // as a 400 ("appAppleId is required when the environment is
        // Production") and took the sandbox verifier down with it — every
        // TestFlight purchase failed. The validator must answer with a
        // refusal from the environments it CAN build, never throw.
        val validator = AppStoreReceiptValidator(
            config = BillingConfig(
                appleBundleId = "com.cards.app",
                appleEnvironment = "Production",
                appleAppAppleId = null,
                googlePackageName = null,
                googleServiceAccountJson = null,
            ),
            rootCertificates = { emptySet() },
            decoder = null,
        )
        val result = validator.validate(receipt())
        assertTrue(
            result is ReceiptValidation.Invalid,
            "a fake token is refused (not verified) — but via a decision, not an exception; got $result",
        )
    }

    @Test
    fun validReceipt_returnsTransactionIdAsOrderId() = runTest {
        val validator = validatorReturning(
            payload().productId("chips_medium").appAccountToken(userId.value).transactionId("txn-42"),
        )
        val result = validator.validate(receipt())
        assertEquals(ReceiptValidation.Valid("txn-42", PurchaseEnvironment.Sandbox), result)
    }

    @Test
    fun productionPayload_reportsProductionEnvironment() = runTest {
        // The decoded payload's own environment claim wins over the verifier
        // that happened to accept it.
        val validator = validatorReturning(
            payload()
                .productId("chips_medium")
                .appAccountToken(userId.value)
                .transactionId("txn-43")
                .environment(Environment.PRODUCTION),
        )
        val result = validator.validate(receipt())
        assertEquals(ReceiptValidation.Valid("txn-43", PurchaseEnvironment.Production), result)
    }

    @Test
    fun environmentMismatch_fallsBackToSiblingVerifier() = runTest {
        // A prod server must keep accepting TestFlight (sandbox) receipts:
        // when the primary verifier rejects the JWS as the wrong environment,
        // the sibling gets a try and the grant records its environment.
        val validator = AppStoreReceiptValidator(
            config = configured(),
            rootCertificates = { emptySet() },
            decoder = { throw VerificationException(VerificationStatus.INVALID_ENVIRONMENT) },
            fallbackDecoder = {
                payload().productId("chips_medium").appAccountToken(userId.value).transactionId("txn-44")
            },
        )
        val result = validator.validate(receipt())
        assertEquals(ReceiptValidation.Valid("txn-44", PurchaseEnvironment.Production), result)
    }

    @Test
    fun nonEnvironmentFailure_doesNotFallBack() = runTest {
        val validator = AppStoreReceiptValidator(
            config = configured(),
            rootCertificates = { emptySet() },
            decoder = { throw VerificationException(VerificationStatus.VERIFICATION_FAILURE) },
            fallbackDecoder = { error("a forged JWS must not get a second try") },
        )
        val result = validator.validate(receipt())
        assertEquals(ReceiptValidation.Invalid("apple_jws_invalid"), result)
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
    fun accountToken_fromInstallLineage_accepted() = runTest {
        // BILL-11: a pack bought before an account upgrade carries the prior
        // identity's id as appAccountToken. With that id in the caller's
        // install lineage, the receipt redeems instead of stranding as
        // apple_account_mismatch.
        val validator = validatorReturning(
            payload().productId("chips_medium").appAccountToken(priorIdentity).transactionId("txn-11"),
        )
        val result = validator.validate(receipt(accountLineage = setOf(UserId(priorIdentity))))
        assertEquals(ReceiptValidation.Valid("txn-11", PurchaseEnvironment.Sandbox), result)
    }

    @Test
    fun accountToken_outsideLineage_stillRejected() = runTest {
        // A token belonging to neither the caller nor any lineage identity is
        // still a hard mismatch — the lineage widens the accepted set, it
        // doesn't disable the binding.
        val validator = validatorReturning(
            payload().productId("chips_medium").appAccountToken(otherUser).transactionId("txn-1"),
        )
        val result = validator.validate(receipt(accountLineage = setOf(UserId(priorIdentity))))
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

    private fun receipt(accountLineage: Set<UserId> = emptySet()) = PurchaseReceipt(
        store = Store.Apple,
        productId = "chip_pack_medium",
        expectedSku = "chips_medium",
        token = "signed-jws",
        userId = userId,
        accountLineage = accountLineage,
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
