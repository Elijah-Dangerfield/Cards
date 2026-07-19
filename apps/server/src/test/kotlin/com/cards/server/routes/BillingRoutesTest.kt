package com.dangerfield.cards.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.server.data.RelaxedGrantRateLimiter
import com.dangerfield.cards.server.domain.BillingEventAction
import com.dangerfield.cards.server.domain.BillingEventAttempt
import com.dangerfield.cards.server.domain.BillingEventsRepository
import com.dangerfield.cards.server.domain.BillingRepository
import com.dangerfield.cards.server.domain.CreateMessageOutcome
import com.dangerfield.cards.server.domain.UserMessage
import com.dangerfield.cards.server.domain.UserMessageKind
import com.dangerfield.cards.server.domain.UserMessageRepository
import com.dangerfield.cards.server.domain.GrantKind
import com.dangerfield.cards.server.domain.PlatformStore
import com.dangerfield.cards.server.domain.Product
import com.dangerfield.cards.server.domain.ProductCatalog
import com.dangerfield.cards.server.domain.ProductCatalogSource
import com.dangerfield.cards.server.domain.Profile
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.PurchaseEnvironment
import com.dangerfield.cards.server.domain.PurchaseReceipt
import com.dangerfield.cards.server.domain.ReceiptValidation
import com.dangerfield.cards.server.domain.ReceiptValidator
import com.dangerfield.cards.server.domain.RedeemResult
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.http.ClientContext
import com.dangerfield.cards.server.plugins.installAuthenticationWithVerifier
import com.dangerfield.cards.server.plugins.installRateLimits
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.util.Date
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Route-level tests for `POST /v1/billing/redeem` (BILL-1). The catalog,
 * receipt validator, and billing repo are faked so the focus is the
 * HTTP/JSON contract + the validate → resolve → grant flow + JWT gating;
 * the Postgres redeem idempotency has its own integration coverage.
 *
 * Mints HS256 JWTs against a controlled secret + matching verifier, the
 * same pattern as [WalletRoutesTest].
 */
@OptIn(ExperimentalTime::class)
class BillingRoutesTest {

    private val testIssuer = "https://test-project.supabase.co/auth/v1"
    private val testSecret = "0123456789abcdef0123456789abcdef0123456789abcdef"
    private val userId = UserId(UUID.fromString("33333333-3333-3333-3333-333333333333"))

    @Test
    fun redeem_validReceipt_grantsCatalogChips_andReturnsBalance() = runTest {
        val billing = FakeBilling()
        callRedeem(
            billing = billing,
            request = RedeemRequest(store = "apple", productId = CHIP_PACK_ID, token = "txn-1"),
            bearer = validJwt(),
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<RedeemResponse>()
            assertEquals(GRANT, body.grantedChips)
            assertEquals(GRANT, body.balance)
            assertFalse(body.alreadyRedeemed)
        }
        // The grant must use the catalog's grantsChips and the validator's
        // order id — never anything the client claimed.
        assertEquals(1, billing.redeemCalls.size)
        assertEquals(GRANT, billing.redeemCalls.single().grantedChips)
        assertEquals("txn-1", billing.redeemCalls.single().orderId)
        assertEquals("apple", billing.redeemCalls.single().store)
        assertEquals(PurchaseEnvironment.Production, billing.redeemCalls.single().environment)
    }

    @Test
    fun redeem_sandboxReceipt_recordsSandboxEnvironment() = runTest {
        val billing = FakeBilling()
        callRedeem(
            billing = billing,
            validator = SandboxEchoValidator,
            request = RedeemRequest(store = "apple", productId = CHIP_PACK_ID, token = "txn-sb"),
            bearer = validJwt(),
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
        }
        assertEquals(PurchaseEnvironment.Sandbox, billing.redeemCalls.single().environment)
    }

    @Test
    fun redeem_replay_reportsAlreadyRedeemed() = runTest {
        val billing = FakeBilling(result = RedeemResult.AlreadyRedeemed(balance = 12_345))
        callRedeem(
            billing = billing,
            request = RedeemRequest(store = "google", productId = CHIP_PACK_ID, token = "txn-2"),
            bearer = validJwt(),
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<RedeemResponse>()
            assertTrue(body.alreadyRedeemed)
            assertEquals(12_345, body.balance)
        }
    }

    @Test
    fun redeem_deadReceipt_returns400_receiptDead_andDoesNotGrant() = runTest {
        // A forged / terminally-rejected receipt classifies Dead: 400
        // `receipt_dead`, which the client acts on by finishing the stuck
        // transaction so it stops replaying (BILL-13).
        val billing = FakeBilling()
        callRedeem(
            billing = billing,
            validator = RejectingValidator,
            request = RedeemRequest(store = "apple", productId = CHIP_PACK_ID, token = "forged"),
            bearer = validJwt(),
        ) { resp ->
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals("receipt_dead", resp.errorCode())
        }
        assertTrue(billing.redeemCalls.isEmpty(), "a forged receipt must never reach the grant")
    }

    @Test
    fun redeem_accountMismatch_returns409_receiptAccountMismatch_andDoesNotGrant() = runTest {
        // A genuine, paid receipt bound to a different one of the user's accounts
        // classifies Mismatch: 409 `receipt_account_mismatch`, which the client
        // treats as recoverable (sign-in-to-claim / grant-on-replay) rather than
        // finishing it as dead.
        val billing = FakeBilling()
        callRedeem(
            billing = billing,
            validator = AccountMismatchValidator,
            request = RedeemRequest(store = "apple", productId = CHIP_PACK_ID, token = "txn-mismatch"),
            bearer = validJwt(),
        ) { resp ->
            assertEquals(HttpStatusCode.Conflict, resp.status)
            assertEquals("receipt_account_mismatch", resp.errorCode())
        }
        assertTrue(billing.redeemCalls.isEmpty(), "a mismatched receipt must never reach the grant")
    }

    @Test
    fun redeem_replayedAccountMismatch_grantsOnReplay_relaxingTheAccountBinding() = runTest {
        // Grant-on-replay: a StoreKit-replayed transaction whose only failure is
        // the account binding is granted to the current caller, flagged relaxed.
        val billing = FakeBilling()
        callRedeem(
            billing = billing,
            validator = ReplayableMismatchValidator,
            request = RedeemRequest(store = "apple", productId = CHIP_PACK_ID, token = "txn-replay", replayed = true),
            bearer = validJwt(),
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<RedeemResponse>()
            assertEquals(GRANT, body.grantedChips)
        }
        val call = billing.redeemCalls.single()
        assertEquals(GrantKind.GrantOnReplay, call.kind, "a grant-on-replay must be recorded as a relaxed grant")
        assertEquals(userId, call.userId, "the grant lands on the current caller, not the receipt owner")
        assertEquals("txn-replay", call.orderId)
    }

    @Test
    fun redeem_recordsBillingEvent_perDisposition() = runTest {
        val grantEvents = RecordingBillingEvents()
        callRedeem(
            billing = FakeBilling(),
            billingEvents = grantEvents,
            request = RedeemRequest(store = "apple", productId = CHIP_PACK_ID, token = "txn-ev"),
            bearer = validJwt(),
        ) { resp -> assertEquals(HttpStatusCode.OK, resp.status) }
        val granted = grantEvents.recorded.single()
        assertEquals(BillingEventAction.Granted, granted.action)
        assertEquals("txn-ev", granted.transactionId)
        assertEquals(userId, granted.callerUser)

        val replayEvents = RecordingBillingEvents()
        callRedeem(
            billing = FakeBilling(),
            validator = ReplayableMismatchValidator,
            billingEvents = replayEvents,
            request = RedeemRequest(store = "apple", productId = CHIP_PACK_ID, token = "txn-rp", replayed = true),
            bearer = validJwt(),
        ) { resp -> assertEquals(HttpStatusCode.OK, resp.status) }
        val relaxed = replayEvents.recorded.single()
        assertEquals(BillingEventAction.GrantedOnReplay, relaxed.action)
        assertEquals(RECEIPT_OWNER, relaxed.receiptOwner, "the relaxed grant records the receipt's owning account")
    }

    @Test
    fun redeem_drainGrant_enqueuesAFoundYourPurchaseMessage() = runTest {
        val messages = RecordingMessages()
        callRedeem(
            billing = FakeBilling(),
            messages = messages,
            request = RedeemRequest(store = "apple", productId = CHIP_PACK_ID, token = "txn-drain", replayed = true),
            bearer = validJwt(),
        ) { resp -> assertEquals(HttpStatusCode.OK, resp.status) }
        assertEquals(1, messages.created.size, "a drain recovery closes the loop with a message")
        assertEquals("billing_granted.apple.txn-drain", messages.created.single().idempotencyKey)
    }

    @Test
    fun redeem_interactiveGrant_doesNotEnqueueAMessage() = runTest {
        // An interactive buy is celebrated by the shop in the moment; a duplicate
        // in-app message would be noise.
        val messages = RecordingMessages()
        callRedeem(
            billing = FakeBilling(),
            messages = messages,
            request = RedeemRequest(store = "apple", productId = CHIP_PACK_ID, token = "txn-live", replayed = false),
            bearer = validJwt(),
        ) { resp -> assertEquals(HttpStatusCode.OK, resp.status) }
        assertTrue(messages.created.isEmpty(), "interactive buys are not messaged")
    }

    @Test
    fun redeem_wedgedEscalation_enqueuesAGoodwillMessage() = runTest {
        val messages = RecordingMessages()
        callRedeem(
            billing = FakeBilling(),
            validator = ReplayableMismatchValidator,
            billingEvents = RecordingBillingEvents(priorAttempts = 10),
            messages = messages,
            request = RedeemRequest(store = "apple", productId = CHIP_PACK_ID, token = "txn-gw", replayed = true),
            bearer = anonymousJwt(),
        ) { resp -> assertEquals(HttpStatusCode.OK, resp.status) }
        assertEquals("billing_goodwill.apple.txn-gw", messages.created.single().idempotencyKey)
    }

    @Test
    fun redeem_wedgedPurchase_escalatesToGoodwillGrant() = runTest {
        // A purchase re-attempted well past the retry cap (here an anonymous
        // caller who never signs in) is made whole with goodwill chips and
        // finished, rather than left paid-but-blocked forever.
        val events = RecordingBillingEvents(priorAttempts = 10)
        val billing = FakeBilling()
        callRedeem(
            billing = billing,
            validator = ReplayableMismatchValidator,
            billingEvents = events,
            request = RedeemRequest(store = "apple", productId = CHIP_PACK_ID, token = "txn-wedged", replayed = true),
            bearer = anonymousJwt(),
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<RedeemResponse>()
            assertTrue(body.goodwill, "a wedged escalation grants goodwill chips")
        }
        val call = billing.redeemCalls.single()
        assertEquals(GrantKind.Goodwill, call.kind, "the escalation grants goodwill, not a normal grant")
        assertTrue(
            events.recorded.any { it.action == BillingEventAction.Escalated },
            "the escalation is recorded for review",
        )
    }

    @Test
    fun redeem_replayedAccountMismatch_anonymousCaller_nudgesSignInToClaim_andDoesNotGrant() = runTest {
        // An anonymous caller is nudged to sign in first (re-login matches the
        // receipt cleanly and lands the chips on the durable account) rather than
        // granted blind on a relaxed binding.
        val billing = FakeBilling()
        callRedeem(
            billing = billing,
            validator = ReplayableMismatchValidator,
            request = RedeemRequest(store = "apple", productId = CHIP_PACK_ID, token = "txn-anon", replayed = true),
            bearer = anonymousJwt(),
        ) { resp ->
            assertEquals(HttpStatusCode.Conflict, resp.status)
            assertEquals("receipt_claim_sign_in", resp.errorCode())
        }
        assertTrue(billing.redeemCalls.isEmpty(), "an anonymous caller is nudged to sign in, never granted blind")
    }

    @Test
    fun redeem_interactiveAccountMismatch_isNotRelaxed_returns409_andDoesNotGrant() = runTest {
        // The same account mismatch on an interactive buy (replayed = false) is
        // never relaxed — only a StoreKit replay is eligible for grant-on-replay.
        val billing = FakeBilling()
        callRedeem(
            billing = billing,
            validator = ReplayableMismatchValidator,
            request = RedeemRequest(store = "apple", productId = CHIP_PACK_ID, token = "txn-interactive", replayed = false),
            bearer = validJwt(),
        ) { resp ->
            assertEquals(HttpStatusCode.Conflict, resp.status)
            assertEquals("receipt_account_mismatch", resp.errorCode())
        }
        assertTrue(billing.redeemCalls.isEmpty(), "an interactive mismatch must never reach the grant")
    }

    @Test
    fun redeem_replayedAccountMismatch_rateLimited_fallsBackTo409() = runTest {
        // Past the per-user relaxed-grant cap, a replayed mismatch stops being
        // relaxed and falls back to the strict 409 so the client still finishes
        // the transaction and the shop is unblocked.
        val limiter = RelaxedGrantRateLimiter(Clock.System)
        repeat(RelaxedGrantRateLimiter.MAX_PER_WINDOW) { limiter.tryAcquire(userId) }
        val billing = FakeBilling()
        callRedeem(
            billing = billing,
            validator = ReplayableMismatchValidator,
            rateLimiter = limiter,
            request = RedeemRequest(store = "apple", productId = CHIP_PACK_ID, token = "txn-capped", replayed = true),
            bearer = validJwt(),
        ) { resp ->
            assertEquals(HttpStatusCode.Conflict, resp.status)
            assertEquals("receipt_account_mismatch", resp.errorCode())
        }
        assertTrue(billing.redeemCalls.isEmpty(), "a rate-limited relaxed grant must not credit")
    }

    @Test
    fun redeem_retryableRejection_returns503_receiptTransient_soClientLeavesTheTransactionReplayable() = runTest {
        // BILL-13: a transient refusal (validator unconfigured / store API
        // unreachable) must answer 503, not 400 — the client maps it to
        // "transient" and keeps the transaction unfinished for a later retry
        // instead of finishing (and stranding) a genuinely paid purchase.
        val billing = FakeBilling()
        callRedeem(
            billing = billing,
            validator = UnconfiguredValidator,
            request = RedeemRequest(store = "apple", productId = CHIP_PACK_ID, token = "txn-unconfigured"),
            bearer = validJwt(),
        ) { resp ->
            assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
            assertEquals("receipt_transient", resp.errorCode())
        }
        assertTrue(billing.redeemCalls.isEmpty(), "an unvalidated receipt must never reach the grant")
    }

    @Test
    fun redeem_resolvesCallerInstallLineage_andHandsItToValidator() = runTest {
        // BILL-11: the route must widen the receipt's accepted account set to
        // the caller's install lineage so a pack bought under a prior identity
        // (same device, pre-upgrade) still redeems.
        val prior = UserId(UUID.fromString("52f3f9c1-1a94-4640-b24c-560a9b7534eb"))
        val capturing = LineageCapturingValidator()
        callRedeem(
            billing = FakeBilling(),
            validator = capturing,
            profiles = LineageProfiles(setOf(userId, prior)),
            request = RedeemRequest(store = "apple", productId = CHIP_PACK_ID, token = "txn-lineage"),
            bearer = validJwt(),
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
        }
        val seen = capturing.seen ?: error("validator was never called")
        assertTrue(prior in seen.accountLineage, "the prior identity must reach the validator's lineage")
        assertTrue(userId in seen.accountLineage, "the caller must be in the resolved lineage")
    }

    @Test
    fun redeem_unknownProduct_returns400_andDoesNotGrant() = runTest {
        val billing = FakeBilling()
        callRedeem(
            billing = billing,
            request = RedeemRequest(store = "apple", productId = "no_such_pack", token = "txn-3"),
            bearer = validJwt(),
        ) { resp ->
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }
        assertTrue(billing.redeemCalls.isEmpty())
    }

    @Test
    fun redeem_unknownStore_returns400() = runTest {
        callRedeem(
            billing = FakeBilling(),
            request = RedeemRequest(store = "amazon", productId = CHIP_PACK_ID, token = "txn-4"),
            bearer = validJwt(),
        ) { resp ->
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }
    }

    @Test
    fun redeem_returns401_whenAuthHeaderMissing() = runTest {
        val billing = FakeBilling()
        callRedeem(
            billing = billing,
            request = RedeemRequest(store = "apple", productId = CHIP_PACK_ID, token = "txn-5"),
            bearer = null,
        ) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
        assertTrue(billing.redeemCalls.isEmpty())
    }

    // ---------- scaffolding ----------

    private fun validJwt(): String = JWT.create()
        .withIssuer(testIssuer)
        .withAudience("authenticated")
        .withSubject(userId.value.toString())
        .withIssuedAt(Date())
        .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
        .sign(Algorithm.HMAC256(testSecret))

    private fun anonymousJwt(): String = JWT.create()
        .withIssuer(testIssuer)
        .withAudience("authenticated")
        .withSubject(userId.value.toString())
        .withClaim("is_anonymous", true)
        .withIssuedAt(Date())
        .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
        .sign(Algorithm.HMAC256(testSecret))

    private val testVerifier = JWT.require(Algorithm.HMAC256(testSecret))
        .withIssuer(testIssuer)
        .withAudience("authenticated")
        .build()

    private suspend fun callRedeem(
        billing: BillingRepository,
        request: RedeemRequest,
        bearer: String?,
        catalog: ProductCatalogSource = SingleChipPackCatalog,
        validator: ReceiptValidator = EchoTokenValidator,
        profiles: ProfileRepository = LineageProfiles(setOf(userId)),
        rateLimiter: RelaxedGrantRateLimiter = RelaxedGrantRateLimiter(Clock.System),
        billingEvents: BillingEventsRepository = RecordingBillingEvents(),
        messages: RecordingMessages = RecordingMessages(),
        assert: suspend (io.ktor.client.statement.HttpResponse) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installRateLimits()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing { billingRoutes(catalog, validator, billing, profiles, rateLimiter, billingEvents, messages) }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val resp = client.post("/v1/billing/redeem") {
                bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            assert(resp)
        }
    }

    private class FakeBilling(
        private val result: RedeemResult = RedeemResult.Granted(balance = GRANT),
    ) : BillingRepository {
        data class Call(
            val userId: UserId,
            val store: String,
            val orderId: String,
            val productId: String,
            val grantedChips: Long,
            val environment: PurchaseEnvironment,
            val kind: GrantKind,
        )

        val redeemCalls: MutableList<Call> = mutableListOf()

        override suspend fun redeem(
            userId: UserId,
            store: String,
            orderId: String,
            productId: String,
            grantedChips: Long,
            environment: PurchaseEnvironment,
            kind: GrantKind,
        ): RedeemResult {
            redeemCalls += Call(userId, store, orderId, productId, grantedChips, environment, kind)
            return result
        }
    }

    private object EchoTokenValidator : ReceiptValidator {
        override suspend fun validate(request: PurchaseReceipt): ReceiptValidation =
            ReceiptValidation.Valid(orderId = request.token, environment = PurchaseEnvironment.Production)
    }

    private object SandboxEchoValidator : ReceiptValidator {
        override suspend fun validate(request: PurchaseReceipt): ReceiptValidation =
            ReceiptValidation.Valid(orderId = request.token, environment = PurchaseEnvironment.Sandbox)
    }

    private object RejectingValidator : ReceiptValidator {
        override suspend fun validate(request: PurchaseReceipt): ReceiptValidation =
            ReceiptValidation.Invalid(reason = "forged")
    }

    private object AccountMismatchValidator : ReceiptValidator {
        override suspend fun validate(request: PurchaseReceipt): ReceiptValidation =
            ReceiptValidation.Invalid(reason = "apple_account_mismatch")
    }

    private object ReplayableMismatchValidator : ReceiptValidator {
        override suspend fun validate(request: PurchaseReceipt): ReceiptValidation =
            ReceiptValidation.AccountMismatch(
                orderId = request.token,
                environment = PurchaseEnvironment.Production,
                receiptOwner = RECEIPT_OWNER,
            )
    }

    private object UnconfiguredValidator : ReceiptValidator {
        override suspend fun validate(request: PurchaseReceipt): ReceiptValidation =
            ReceiptValidation.Invalid(reason = "apple_validator_unconfigured", retryable = true)
    }

    private class LineageCapturingValidator : ReceiptValidator {
        var seen: PurchaseReceipt? = null
        override suspend fun validate(request: PurchaseReceipt): ReceiptValidation {
            seen = request
            return ReceiptValidation.Valid(orderId = request.token, environment = PurchaseEnvironment.Production)
        }
    }

    @OptIn(ExperimentalTime::class)
    private class RecordingMessages : UserMessageRepository {
        data class Created(val idempotencyKey: String, val title: String, val body: String)
        val created: MutableList<Created> = mutableListOf()
        override suspend fun create(
            id: UUID,
            userId: UserId,
            idempotencyKey: String,
            kind: UserMessageKind,
            emoji: String?,
            title: String,
            body: String,
            deepLink: String?,
            expiresAt: kotlin.time.Instant?,
        ): CreateMessageOutcome {
            created += Created(idempotencyKey, title, body)
            return CreateMessageOutcome(
                message = UserMessage(
                    id = id,
                    userId = userId,
                    idempotencyKey = idempotencyKey,
                    kind = kind,
                    emoji = emoji,
                    title = title,
                    body = body,
                    deepLink = deepLink,
                    createdAt = kotlin.time.Instant.fromEpochMilliseconds(0),
                    expiresAt = expiresAt,
                    ackedAt = null,
                ),
                wasAlreadyCreated = false,
            )
        }
        override suspend fun unreadFor(userId: UserId, now: kotlin.time.Instant, limit: Int) = notNeeded()
        override suspend fun ackMany(userId: UserId, ids: List<UUID>, at: kotlin.time.Instant) = notNeeded()
        override suspend fun sweepExpiredAndAcked(now: kotlin.time.Instant) = notNeeded()
        override suspend fun deleteAllForUser(userId: UserId) = notNeeded()
        private fun notNeeded(): Nothing = error("not needed for billing redeem")
    }

    private class RecordingBillingEvents(
        private val priorAttempts: Int = 0,
    ) : BillingEventsRepository {
        val recorded: MutableList<BillingEventAttempt> = mutableListOf()
        override suspend fun record(event: BillingEventAttempt) {
            recorded += event
        }
        override suspend fun attemptCountFor(store: String, transactionId: String): Int =
            priorAttempts + recorded.count { it.store == store && it.transactionId == transactionId }
    }

    private class LineageProfiles(private val lineage: Set<UserId>) : ProfileRepository {
        override suspend fun findInstallLineage(userId: UserId): Set<UserId> = lineage
        override suspend fun findOrCreate(userId: UserId): Profile = notNeeded()
        override suspend fun findById(userId: UserId): Profile = notNeeded()
        override suspend fun update(
            userId: UserId,
            displayName: String?,
            avatarEmoji: String?,
            avatarBackgroundColor: String?,
            clearAvatarBackgroundColor: Boolean,
        ) = notNeeded()
        override suspend fun delete(userId: UserId) = notNeeded()
        override suspend fun touchInstallId(userId: UserId, installId: UUID) = notNeeded()
        override suspend fun findInstallSiblings(installId: UUID, currentUserId: UserId) = notNeeded()
        private fun notNeeded(): Nothing = error("not needed for billing redeem")
    }

    private object SingleChipPackCatalog : ProductCatalogSource {
        override suspend fun read(context: ClientContext): ProductCatalog =
            ProductCatalog(chipPacks = listOf(chipPack()), chipOffers = emptyList())

        override suspend fun readById(id: String, context: ClientContext): Product? =
            chipPack().takeIf { it.id == id }

        private fun chipPack() = Product.ChipPack(
            id = CHIP_PACK_ID,
            titleByLocale = mapOf("en" to "Tall Stack"),
            subtitleByLocale = mapOf("en" to "30,000 chips"),
            iconEmoji = "💰",
            grantsChips = GRANT,
            store = PlatformStore(
                ios = PlatformStore.StoreSku("chips_medium", "$4.99"),
                android = PlatformStore.StoreSku("chips_medium", "$4.99"),
            ),
        )
    }

    private suspend fun io.ktor.client.statement.HttpResponse.errorCode(): String =
        body<ProblemResponse>().error.code

    @kotlinx.serialization.Serializable
    private data class ProblemResponse(val error: ProblemError)

    @kotlinx.serialization.Serializable
    private data class ProblemError(val code: String, val message: String)

    private companion object {
        const val CHIP_PACK_ID = "chip_pack_medium"
        const val GRANT = 30_000L
        val RECEIPT_OWNER = UserId(UUID.fromString("52f3f9c1-1a94-4640-b24c-560a9b7534eb"))
    }
}
