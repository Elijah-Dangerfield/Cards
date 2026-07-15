package com.dangerfield.cards.libraries.billing.impl

import com.dangerfield.cards.libraries.billing.BillingPlatform
import com.dangerfield.cards.libraries.billing.PurchaseTransaction
import com.dangerfield.cards.libraries.billing.RedeemOutcome
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.networking.InternalNetworkingApi
import com.dangerfield.cards.libraries.networking.NetworkClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BILL-12 — [BillingRepositoryImpl.redeem] must translate the server's HTTP
 * status into the right [RedeemOutcome]. The production client runs with
 * `expectSuccess = true`, so a 4xx throws before any `when(response.status)`
 * branch can read it — the repo has to map the thrown [io.ktor.client.plugins.ClientRequestException]
 * itself. These tests pin that mapping:
 *
 *  - A definitive 400 `receipt_rejected` is a hard failure the user must see
 *    ([RedeemOutcome.Rejected]) — never a transient "chips on the way" that
 *    retries forever ([RedeemOutcome.Unavailable]).
 *  - A 5xx / unreachable server stays [RedeemOutcome.Unavailable] so the
 *    launch-time redeemer can recover the paid-for purchase later.
 */
class BillingRepositoryImplTest : CoroutineTest() {

    private val transaction = PurchaseTransaction(
        sku = "chips_small",
        orderId = "2000001203481803",
        purchaseToken = "signed-jws",
        platform = BillingPlatform.Apple,
        purchasedAtEpochMs = 1_700_000_000_000L,
    )

    @Test
    fun redeem_badRequestReceiptRejected_isRejectedNotUnavailable() = runUnitTest {
        val repo = buildRepo { _ ->
            respondJson(
                """{"error":{"code":"receipt_rejected","message":"The purchase receipt could not be verified."}}""",
                status = HttpStatusCode.BadRequest,
            )
        }
        val outcome = repo.redeem("chip_pack_small", transaction)
        assertEquals(RedeemOutcome.Rejected, outcome)
    }

    @Test
    fun redeem_ok_returnsGrantedWithServerBalance() = runUnitTest {
        val repo = buildRepo { _ ->
            respondJson("""{"balance":5000,"grantedChips":1000,"alreadyRedeemed":false}""")
        }
        val outcome = repo.redeem("chip_pack_small", transaction)
        assertEquals(RedeemOutcome.Granted(balance = 5000, grantedChips = 1000, alreadyRedeemed = false), outcome)
    }

    @Test
    fun redeem_serverError_isUnavailable() = runUnitTest {
        val repo = buildRepo { _ ->
            respondJson("""{}""", status = HttpStatusCode.InternalServerError)
        }
        val outcome = repo.redeem("chip_pack_small", transaction)
        assertEquals(RedeemOutcome.Unavailable, outcome)
    }

    @Test
    fun redeem_fakePlatform_neverHitsWireAndIsUnavailable() = runUnitTest {
        var hit = false
        val repo = buildRepo { _ ->
            hit = true
            respondJson("""{}""")
        }
        val outcome = repo.redeem("chip_pack_small", transaction.copy(platform = BillingPlatform.Fake))
        assertEquals(RedeemOutcome.Unavailable, outcome)
        assertTrue(!hit, "a Fake transaction must not reach the redeem endpoint")
    }

    private fun buildRepo(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): BillingRepositoryImpl {
        val httpClient = HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        coerceInputValues = true
                    },
                )
            }
            expectSuccess = true
        }
        @OptIn(InternalNetworkingApi::class)
        val networkClient = object : NetworkClient {
            override val client: HttpClient = httpClient
            override val authenticatedClient: HttpClient = httpClient
            override suspend fun awaitAuthReady() = Unit
        }
        return BillingRepositoryImpl(networkClient)
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = ByteReadChannel(body),
        status = status,
        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
    )
}
