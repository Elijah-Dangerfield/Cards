package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.config.BillingConfig
import com.dangerfield.cards.server.domain.PurchaseReceipt
import com.dangerfield.cards.server.domain.ReceiptValidation
import com.dangerfield.cards.server.domain.UserId
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.androidpublisher.AndroidPublisher
import com.google.api.services.androidpublisher.AndroidPublisherScopes
import com.google.api.services.androidpublisher.model.ProductPurchase
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Verifies a Google Play purchase token by calling the Play Developer API's
 * `purchases.products.get` with a service-account credential. The token alone
 * proves nothing — only the server-side lookup confirms the purchase is real,
 * paid, and bound to the right user.
 *
 * Dormant until [BillingConfig.googlePackageName] and
 * [BillingConfig.googleServiceAccountJson] are both set: an unconfigured
 * server refuses every Google receipt. On a successful lookup we enforce
 * `purchaseState == PURCHASED` and `obfuscatedExternalAccountId == userId`
 * (the user binding — pinning to the order id would let one user redeem
 * another's receipt). The product SKU is implicit: the token is looked up
 * against [PurchaseReceipt.expectedSku], so a token for a different product
 * fails the lookup.
 *
 * Acknowledging / consuming the purchase is the client's job (Play Billing
 * `consumeAsync` for the consumable chip packs, BILL-3) — this validator only
 * reads.
 */
class GooglePlayReceiptValidator(
    private val config: BillingConfig,
    private val lookupFactory: (packageName: String, serviceAccountJson: String) -> PurchaseLookup =
        ::buildLookup,
) {
    /**
     * Looks up a Play purchase by SKU + token. The production lookup calls
     * the Play Developer API's `purchases.products.get`; tests inject a stub
     * to exercise the post-lookup invariants without a live API call.
     */
    fun interface PurchaseLookup {
        @Throws(GoogleJsonResponseException::class)
        fun get(sku: String, token: String): ProductPurchase
    }

    private val logger = LoggerFactory.getLogger("GooglePlayReceiptValidator")

    private val lookup: PurchaseLookup? by lazy { buildConfigured() }

    suspend fun validate(request: PurchaseReceipt): ReceiptValidation {
        val lookup = lookup
            ?: return ReceiptValidation.Invalid("google_validator_unconfigured")

        val purchase = try {
            withContext(Dispatchers.IO) { lookup.get(request.expectedSku, request.token) }
        } catch (e: GoogleJsonResponseException) {
            logger.warn("Google purchase lookup failed: HTTP {}", e.statusCode)
            return ReceiptValidation.Invalid("google_lookup_failed")
        }

        if (purchase.purchaseState != PURCHASE_STATE_PURCHASED) {
            return ReceiptValidation.Invalid("google_not_purchased")
        }

        val accountId = purchase.obfuscatedExternalAccountId
        if (accountId == null || !accountId.matchesUser(request.userId)) {
            return ReceiptValidation.Invalid("google_account_mismatch")
        }

        val orderId = purchase.orderId
            ?: return ReceiptValidation.Invalid("google_missing_order_id")

        return ReceiptValidation.Valid(orderId = orderId)
    }

    private fun buildConfigured(): PurchaseLookup? {
        val packageName = config.googlePackageName?.takeIf { it.isNotBlank() }
        val serviceAccountJson = config.googleServiceAccountJson?.takeIf { it.isNotBlank() }
        if (packageName == null || serviceAccountJson == null) {
            logger.warn(
                "GOOGLE_PLAY_PACKAGE_NAME / GOOGLE_PLAY_SERVICE_ACCOUNT_JSON not set — " +
                    "Google receipt validation disabled.",
            )
            return null
        }
        return lookupFactory(packageName, serviceAccountJson)
    }

    private fun String.matchesUser(userId: UserId): Boolean {
        val parsed = parseUuidOrNull(this) ?: return false
        return UserId(parsed) == userId
    }

    private fun parseUuidOrNull(value: String): UUID? =
        try {
            UUID.fromString(value)
        } catch (_: IllegalArgumentException) {
            null
        }

    private companion object {
        const val PURCHASE_STATE_PURCHASED = 0

        fun buildLookup(packageName: String, serviceAccountJson: String): PurchaseLookup {
            val credentials = GoogleCredentials
                .fromStream(serviceAccountJson.byteInputStream())
                .createScoped(listOf(AndroidPublisherScopes.ANDROIDPUBLISHER))
            val publisher = AndroidPublisher.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                HttpCredentialsAdapter(credentials),
            ).setApplicationName(packageName).build()
            return PurchaseLookup { sku, token ->
                publisher.purchases().products().get(packageName, sku, token).execute()
            }
        }
    }
}
