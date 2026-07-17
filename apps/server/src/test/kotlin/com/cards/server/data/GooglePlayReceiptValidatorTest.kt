package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.config.BillingConfig
import com.dangerfield.cards.server.domain.PurchaseEnvironment
import com.dangerfield.cards.server.domain.PurchaseReceipt
import com.dangerfield.cards.server.domain.ReceiptValidation
import com.dangerfield.cards.server.domain.Store
import com.dangerfield.cards.server.domain.UserId
import com.google.api.services.androidpublisher.model.ProductPurchase
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * BILL-2 — unit coverage for the Google Play receipt validator. The Play
 * Developer API call is stubbed via the [GooglePlayReceiptValidator.PurchaseLookup]
 * seam so the tests pin the invariants we enforce on the looked-up purchase:
 * dormant-until-configured, `purchaseState == PURCHASED`, and the user binding
 * via `obfuscatedExternalAccountId`.
 */
class GooglePlayReceiptValidatorTest {

    private val userId = UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val otherUser = "22222222-2222-2222-2222-222222222222"

    @Test
    fun unconfigured_refusesValidation() = runTest {
        val validator = GooglePlayReceiptValidator(
            config = unconfigured(),
            lookupFactory = { _, _ -> error("lookup must never build when unconfigured") },
        )
        val result = validator.validate(receipt())
        // Retryable: an unconfigured validator must not make the client finish
        // (and strand) the token — it validates once the credentials are set.
        assertEquals(ReceiptValidation.Invalid("google_validator_unconfigured", retryable = true), result)
    }

    @Test
    fun purchasedAndBoundToUser_returnsOrderId() = runTest {
        val validator = validatorReturning(
            purchase(state = 0, accountId = userId.value.toString(), orderId = "GPA.1234"),
        )
        val result = validator.validate(receipt())
        assertEquals(ReceiptValidation.Valid("GPA.1234", PurchaseEnvironment.Production), result)
    }

    @Test
    fun licenseTesterPurchase_isSandbox() = runTest {
        // purchaseType 0 = test purchase (license testers) — Play's sandbox
        // equivalent. The tester paid nothing; the grant must not read as revenue.
        val validator = validatorReturning(
            purchase(state = 0, accountId = userId.value.toString(), orderId = "GPA.test")
                .setPurchaseType(0),
        )
        val result = validator.validate(receipt())
        assertEquals(ReceiptValidation.Valid("GPA.test", PurchaseEnvironment.Sandbox), result)
    }

    @Test
    fun pendingPurchase_rejected_andNeverGrants() = runTest {
        val validator = validatorReturning(
            purchase(state = 2, accountId = userId.value.toString(), orderId = "GPA.1"),
        )
        val result = validator.validate(receipt())
        assertEquals(ReceiptValidation.Invalid("google_not_purchased"), result)
    }

    @Test
    fun canceledPurchase_rejected() = runTest {
        val validator = validatorReturning(
            purchase(state = 1, accountId = userId.value.toString(), orderId = "GPA.1"),
        )
        val result = validator.validate(receipt())
        assertEquals(ReceiptValidation.Invalid("google_not_purchased"), result)
    }

    @Test
    fun wrongAccountId_rejected() = runTest {
        val validator = validatorReturning(
            purchase(state = 0, accountId = otherUser, orderId = "GPA.1"),
        )
        val result = validator.validate(receipt())
        assertEquals(ReceiptValidation.Invalid("google_account_mismatch"), result)
    }

    @Test
    fun missingAccountId_rejected() = runTest {
        val validator = validatorReturning(
            purchase(state = 0, accountId = null, orderId = "GPA.1"),
        )
        val result = validator.validate(receipt())
        assertEquals(ReceiptValidation.Invalid("google_account_mismatch"), result)
    }

    private fun validatorReturning(purchase: ProductPurchase) =
        GooglePlayReceiptValidator(
            config = configured(),
            lookupFactory = { _, _ -> GooglePlayReceiptValidator.PurchaseLookup { _, _ -> purchase } },
        )

    private fun purchase(state: Int, accountId: String?, orderId: String?): ProductPurchase =
        ProductPurchase()
            .setPurchaseState(state)
            .setObfuscatedExternalAccountId(accountId)
            .setOrderId(orderId)

    private fun receipt() = PurchaseReceipt(
        store = Store.Google,
        productId = "chip_pack_medium",
        expectedSku = "chips_medium",
        token = "purchase-token",
        userId = userId,
    )

    private fun configured() = BillingConfig(
        appleBundleId = null,
        appleEnvironment = "Sandbox",
        appleAppAppleId = null,
        googlePackageName = "com.cards.app",
        googleServiceAccountJson = """{"type":"service_account"}""",
    )

    private fun unconfigured() = BillingConfig(
        appleBundleId = null,
        appleEnvironment = "Sandbox",
        appleAppAppleId = null,
        googlePackageName = null,
        googleServiceAccountJson = null,
    )
}
