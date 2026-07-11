package com.dangerfield.cards.server.data

import com.apple.itunes.storekit.model.Environment
import com.apple.itunes.storekit.model.JWSTransactionDecodedPayload
import com.apple.itunes.storekit.verification.SignedDataVerifier
import com.apple.itunes.storekit.verification.VerificationException
import com.apple.itunes.storekit.verification.VerificationStatus
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.server.config.BillingConfig
import com.dangerfield.cards.server.domain.PurchaseEnvironment
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
 * **Environments:** verification is tried against the configured
 * [BillingConfig.appleEnvironment] first, then falls back to the sibling
 * (production ↔ sandbox) when the JWS was signed for the other one. This is
 * how one server serves both populations forever: TestFlight installs of the
 * production app always transact in Apple's sandbox, so a prod server pinned
 * to production-only would start rejecting every tester the day
 * `APPLE_STORE_ENVIRONMENT` flips at launch. Which environment actually
 * verified the receipt is reported on [ReceiptValidation.Valid] so the grant
 * can be recorded as sandbox (free chips, not revenue) vs production.
 *
 * The stable transaction id becomes the `(store, order_id)` idempotency key.
 */
class AppStoreReceiptValidator(
    private val config: BillingConfig,
    private val rootCertificates: () -> Set<InputStream> = ::loadAppleRootCertificates,
    decoder: TransactionDecoder? = null,
    fallbackDecoder: TransactionDecoder? = null,
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

    private class EnvironmentDecoder(
        val environment: PurchaseEnvironment,
        val decoder: TransactionDecoder,
    )

    private val logger = LoggerFactory.getLogger("AppStoreReceiptValidator")

    private val injectedDecoders: List<EnvironmentDecoder>? = decoder?.let { primary ->
        listOfNotNull(
            EnvironmentDecoder(configuredEnvironment() ?: PurchaseEnvironment.Sandbox, primary),
            fallbackDecoder?.let { EnvironmentDecoder(siblingOf(configuredEnvironment()), it) },
        )
    }

    private val resolvedDecoders: List<EnvironmentDecoder> by lazy {
        injectedDecoders ?: buildDecoders()
    }

    suspend fun validate(request: PurchaseReceipt): ReceiptValidation {
        val decoders = resolvedDecoders
        if (decoders.isEmpty()) return ReceiptValidation.Invalid("apple_validator_unconfigured")

        var payload: JWSTransactionDecodedPayload? = null
        var verifiedBy: EnvironmentDecoder? = null
        for (candidate in decoders) {
            try {
                payload = withContext(Dispatchers.IO) { candidate.decoder.decode(request.token) }
                verifiedBy = candidate
                break
            } catch (e: VerificationException) {
                if (e.status == VerificationStatus.INVALID_ENVIRONMENT && candidate !== decoders.last()) {
                    logger.info(
                        "Apple JWS is for the other environment (tried {}) — retrying against the sibling",
                        candidate.environment.wire,
                    )
                    continue
                }
                logger.warn("Apple JWS verification failed: {}", e.status)
                return ReceiptValidation.Invalid("apple_jws_invalid")
            }
        }
        if (payload == null || verifiedBy == null) {
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

        return ReceiptValidation.Valid(
            orderId = transactionId,
            // The decoded payload is authoritative when it names its
            // environment; otherwise credit the verifier that accepted it.
            environment = payload.environment?.toPurchaseEnvironment() ?: verifiedBy.environment,
        )
    }

    private fun buildDecoders(): List<EnvironmentDecoder> {
        val bundleId = config.appleBundleId?.takeIf { it.isNotBlank() } ?: run {
            logger.warn("APPLE_BUNDLE_ID not set — Apple receipt validation disabled.")
            return emptyList()
        }
        val primary = parseEnvironment(config.appleEnvironment) ?: run {
            logger.warn(
                "APPLE_STORE_ENVIRONMENT='{}' is not a valid StoreKit environment — Apple validation disabled.",
                config.appleEnvironment,
            )
            return emptyList()
        }
        val environments = when (primary) {
            Environment.PRODUCTION -> listOf(Environment.PRODUCTION, Environment.SANDBOX)
            Environment.SANDBOX -> listOf(Environment.SANDBOX, Environment.PRODUCTION)
            // XCODE / LOCAL_TESTING are single-environment dev setups.
            else -> listOf(primary)
        }
        // The verifiers share one certificate load; SignedDataVerifier reads
        // the streams at construction so each needs a fresh set. Construction
        // is per-environment best-effort: Apple's library refuses to build a
        // PRODUCTION verifier without the numeric appAppleId, and before this
        // guard that IllegalArgumentException escaped the first redeem as a
        // 400 — taking the sandbox verifier down with it, so every TestFlight
        // purchase failed (BILL-7). Verify with whatever CAN be built and log
        // the gap loudly.
        return environments.mapNotNull { environment ->
            val verifier = Catching { buildVerifier(bundleId, environment) }.getOrElse { e ->
                logger.warn(
                    "Cannot build the {} receipt verifier ({}) — set APPLE_APP_APPLE_ID to enable it; " +
                        "continuing with the remaining environment(s)",
                    environment, e.message,
                )
                return@mapNotNull null
            }
            EnvironmentDecoder(
                environment = environment.toPurchaseEnvironment(),
                decoder = TransactionDecoder { verifier.verifyAndDecodeTransaction(it) },
            )
        }
    }

    private fun buildVerifier(bundleId: String, environment: Environment): SignedDataVerifier {
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

    private fun configuredEnvironment(): PurchaseEnvironment? =
        parseEnvironment(config.appleEnvironment)?.toPurchaseEnvironment()

    private fun siblingOf(environment: PurchaseEnvironment?): PurchaseEnvironment =
        when (environment) {
            PurchaseEnvironment.Production -> PurchaseEnvironment.Sandbox
            PurchaseEnvironment.Sandbox, null -> PurchaseEnvironment.Production
        }

    private fun Environment.toPurchaseEnvironment(): PurchaseEnvironment = when (this) {
        Environment.PRODUCTION -> PurchaseEnvironment.Production
        // Xcode / local-testing transactions are dev artifacts — never revenue.
        else -> PurchaseEnvironment.Sandbox
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
