package com.dangerfield.cards.libraries.billing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StoreKitCoordinatorMappingTest {

    @Test
    fun success_mapsToSuccessWithAppleTransaction() {
        val result = StoreKitPurchaseResult(
            status = StoreKitPurchaseStatus.Success,
            productId = "com.cards.iap.chips.small",
            transactionId = "2000000123456789",
            jwsRepresentation = "signed.jws.payload",
            purchasedAtEpochMs = 1_700_000_000_000,
            displayPrice = "$0.99",
        ).toPurchaseResult()

        val success = assertIs<PurchaseResult.Success>(result)
        val tx = success.transaction
        assertEquals("com.cards.iap.chips.small", tx.sku)
        assertEquals("2000000123456789", tx.orderId)
        assertEquals("signed.jws.payload", tx.purchaseToken)
        assertEquals(BillingPlatform.Apple, tx.platform)
        assertEquals(1_700_000_000_000, tx.purchasedAtEpochMs)
        assertEquals("$0.99", tx.displayPrice)
    }

    @Test
    fun success_jwsBecomesPurchaseToken_soConsumeAndRedeemShareOneId() {
        val result = StoreKitPurchaseResult(
            status = StoreKitPurchaseStatus.Success,
            productId = "chips_medium",
            transactionId = "tx-1",
            jwsRepresentation = "the.jws",
        ).toPurchaseResult()

        val tx = assertIs<PurchaseResult.Success>(result).transaction
        assertEquals("the.jws", tx.purchaseToken)
        assertTrue(tx.orderId != tx.purchaseToken)
    }

    @Test
    fun alreadyPurchased_mapsToAlreadyOwned() {
        val result = StoreKitPurchaseResult(
            status = StoreKitPurchaseStatus.AlreadyPurchased,
            productId = "chips_large",
            transactionId = "tx-2",
            jwsRepresentation = "jws-2",
        ).toPurchaseResult()

        val owned = assertIs<PurchaseResult.AlreadyOwned>(result)
        assertEquals("chips_large", owned.transaction.sku)
    }

    @Test
    fun success_withoutVerifiedTransaction_downgradesToFailed_notUnverifiedCredit() {
        val result = StoreKitPurchaseResult(
            status = StoreKitPurchaseStatus.Success,
            productId = "chips_small",
            transactionId = null,
            jwsRepresentation = null,
        ).toPurchaseResult()

        assertIs<PurchaseResult.Failed>(result)
    }

    @Test
    fun userCancelled_mapsToUserCancelled() {
        val result = StoreKitPurchaseResult(status = StoreKitPurchaseStatus.UserCancelled).toPurchaseResult()
        assertEquals(PurchaseResult.UserCancelled, result)
    }

    @Test
    fun pending_mapsToFailedWithReason() {
        val result = StoreKitPurchaseResult(
            status = StoreKitPurchaseStatus.Pending,
            errorMessage = "awaiting ask-to-buy",
        ).toPurchaseResult()

        assertEquals("awaiting ask-to-buy", assertIs<PurchaseResult.Failed>(result).reason)
    }

    @Test
    fun failed_carriesErrorMessage() {
        val result = StoreKitPurchaseResult(
            status = StoreKitPurchaseStatus.Failed,
            errorMessage = "network down",
        ).toPurchaseResult()

        assertEquals("network down", assertIs<PurchaseResult.Failed>(result).reason)
    }

    @Test
    fun product_mapsAllFields() {
        val product = StoreKitProduct(
            productId = "com.cards.iap.chips.small",
            displayPrice = "$0.99",
            currencyCode = "USD",
            priceMicros = 990_000,
        ).toBillingProduct()

        assertEquals("com.cards.iap.chips.small", product.sku)
        assertEquals("$0.99", product.displayPrice)
        assertEquals("USD", product.currencyCode)
        assertEquals(990_000, product.priceMicros)
    }
}
