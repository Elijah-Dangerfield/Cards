package com.dangerfield.cards.server.data

import com.apple.itunes.storekit.model.Environment
import com.apple.itunes.storekit.model.JWSTransactionDecodedPayload
import com.apple.itunes.storekit.verification.SignedDataVerifier
import com.apple.itunes.storekit.verification.VerificationException
import com.dangerfield.cards.server.config.BillingConfig
import com.dangerfield.cards.server.domain.PurchaseReceipt
import com.dangerfield.cards.server.domain.ReceiptValidation
import com.dangerfield.cards.server.domain.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.InputStream

/**
 * Verifies an Apple StoreKit 2 signed-transaction JWS using the official
 * `app-store-server-library`. The JWS is self-contained: the library checks
 * its x5c certificate chain against the bundled Apple root CAs, so a forged
 * or tampered transaction is rejected offline — no App Store Connect API key
 * is needed for transaction verification.
 *
 * Dormant until [BillingConfig.appleBundleId] is set: an unconfigured server
 * refuses every Apple receipt rather than trusting one. After the JWS
 * verifies, we additionally enforce the business invariants the library does
 * not: the decoded `productId` matches the SKU we expect for the redeemed
 * catalog product, the `appAccountToken` equals the authenticated caller
 * (the user binding — pinning to the order id would let one user redeem
 * another's receipt), and the transaction is not revoked/refunded.
 *
 * The stable transaction id becomes the `(store, order_id)` idempotency key.
 */
class AppStoreReceiptValidator(
    private val config: BillingConfig,
    private val rootCertificates: () -> Set<InputStream> = ::loadAppleRootCertificates,
    private val decoder: TransactionDecoder? = null,
) {
    /**
     * Verifies a signed-transaction JWS and decodes it. The production
     * decoder is Apple's [SignedDataVerifier.verifyAndDecodeTransaction];
     * tests inject a stub to exercise the post-verification invariants
     * without a real signed receipt.
     */
    fun interface TransactionDecoder {
        @Throws(VerificationException::class)
        fun decode(signedTransaction: String): JWSTransactionDecodedPayload
    }

    private val logger = LoggerFactory.getLogger("AppStoreReceiptValidator")

    private val resolvedDecoder: TransactionDecoder? by lazy {
        decoder ?: buildVerifier()?.let { verifier ->
            TransactionDecoder { verifier.verifyAndDecodeTransaction(it) }
        }
    }

    suspend fun validate(request: PurchaseReceipt): ReceiptValidation {
        val decoder = resolvedDecoder
            ?: return ReceiptValidation.Invalid("apple_validator_unconfigured")

        val payload = try {
            withContext(Dispatchers.IO) { decoder.decode(request.token) }
        } catch (e: VerificationException) {
            logger.warn("Apple JWS verification failed: {}", e.status)
            return ReceiptValidation.Invalid("apple_jws_invalid")
        }

        if (payload.productId != request.expectedSku) {
            logger.warn(
                "Apple receipt product mismatch: JWS carries '{}', expected '{}'",
                payload.productId, request.expectedSku,
            )
            return ReceiptValidation.Invalid("apple_product_mismatch")
        }

        val accountToken = payload.appAccountToken
        if (accountToken == null || UserId(accountToken) != request.userId) {
            logger.warn(
                "Apple receipt account mismatch: appAccountToken={} caller={}",
                accountToken ?: "<absent>", request.userId.value,
            )
            return ReceiptValidation.Invalid("apple_account_mismatch")
        }

        if (payload.revocationDate != null) {
            logger.warn("Apple receipt revoked/refunded (transactionId={})", payload.transactionId)
            return ReceiptValidation.Invalid("apple_revoked")
        }

        val transactionId = payload.transactionId
            ?: run {
                logger.warn("Apple receipt verified but missing transactionId (product={})", payload.productId)
                return ReceiptValidation.Invalid("apple_missing_transaction_id")
            }

        return ReceiptValidation.Valid(orderId = transactionId)
    }

    private fun buildVerifier(): SignedDataVerifier? {
        val bundleId = config.appleBundleId?.takeIf { it.isNotBlank() } ?: run {
            logger.warn("APPLE_BUNDLE_ID not set — Apple receipt validation disabled.")
            return null
        }
        val environment = parseEnvironment(config.appleEnvironment) ?: run {
            logger.warn(
                "APPLE_STORE_ENVIRONMENT='{}' is not a valid StoreKit environment — Apple validation disabled.",
                config.appleEnvironment,
            )
            return null
        }
        // Online checks (revocation + expiry against the App Store) require the
        // numeric appAppleId, which only exists for a published app. Enable
        // them when it is configured; otherwise rely on the offline chain +
        // our own revocationDate check.
        val enableOnlineChecks = config.appleAppAppleId != null
        return SignedDataVerifier(
            rootCertificates(),
            bundleId,
            config.appleAppAppleId,
            environment,
            enableOnlineChecks,
        )
    }

    private fun parseEnvironment(value: String): Environment? = when (value.lowercase()) {
        "sandbox" -> Environment.SANDBOX
        "production", "prod" -> Environment.PRODUCTION
        "xcode" -> Environment.XCODE
        "localtesting", "local" -> Environment.LOCAL_TESTING
        else -> null
    }

    private companion object {
        val APPLE_ROOT_CERT_RESOURCES = listOf(
            "/apple-certs/AppleRootCA-G3.cer",
            "/apple-certs/AppleIncRootCertificate.cer",
            "/apple-certs/AppleComputerRootCertificate.cer",
        )

        fun loadAppleRootCertificates(): Set<InputStream> =
            APPLE_ROOT_CERT_RESOURCES.mapNotNull { path ->
                AppStoreReceiptValidator::class.java.getResourceAsStream(path)
            }.toSet()
    }
}
