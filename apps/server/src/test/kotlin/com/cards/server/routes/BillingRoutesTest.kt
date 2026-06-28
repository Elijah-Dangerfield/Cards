package com.dangerfield.cards.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.server.domain.BillingRepository
import com.dangerfield.cards.server.domain.PlatformStore
import com.dangerfield.cards.server.domain.Product
import com.dangerfield.cards.server.domain.ProductCatalog
import com.dangerfield.cards.server.domain.ProductCatalogSource
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
    fun redeem_rejectedReceipt_returns400_andDoesNotGrant() = runTest {
        val billing = FakeBilling()
        callRedeem(
            billing = billing,
            validator = RejectingValidator,
            request = RedeemRequest(store = "apple", productId = CHIP_PACK_ID, token = "forged"),
            bearer = validJwt(),
        ) { resp ->
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }
        assertTrue(billing.redeemCalls.isEmpty(), "a forged receipt must never reach the grant")
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
        assert: suspend (io.ktor.client.statement.HttpResponse) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installRateLimits()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing { billingRoutes(catalog, validator, billing) }
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
        )

        val redeemCalls: MutableList<Call> = mutableListOf()

        override suspend fun redeem(
            userId: UserId,
            store: String,
            orderId: String,
            productId: String,
            grantedChips: Long,
        ): RedeemResult {
            redeemCalls += Call(userId, store, orderId, productId, grantedChips)
            return result
        }
    }

    private object EchoTokenValidator : ReceiptValidator {
        override suspend fun validate(request: PurchaseReceipt): ReceiptValidation =
            ReceiptValidation.Valid(orderId = request.token)
    }

    private object RejectingValidator : ReceiptValidator {
        override suspend fun validate(request: PurchaseReceipt): ReceiptValidation =
            ReceiptValidation.Invalid(reason = "forged")
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

    private companion object {
        const val CHIP_PACK_ID = "chip_pack_medium"
        const val GRANT = 30_000L
    }
}
