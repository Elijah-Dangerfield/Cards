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
 *  - A definitive 400 `receipt_rejected` is a terminal failure the user must
 *    see ([RedeemOutcome.RejectedTerminal]) — the caller finishes the stuck
 *    transaction rather than replaying it forever (BILL-13) — never a transient
 *    "chips on the way" that retries ([RedeemOutcome.Unavailable]).
 *  - A non-terminal 4xx (unknown_product / catalog drift) is [RedeemOutcome.Rejected]:
 *    no credit, but the transaction is left unfinished for a later retry.
 *  - A 5xx / 503 / unreachable server stays [RedeemOutcome.Unavailable] so the
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
    fun redeem_badRequestReceiptRejected_isTerminalSoTheStuckTransactionGetsFinished() = runUnitTest {
        val repo = buildRepo { _ ->
            respondJson(
                """{"error":{"code":"receipt_rejected","message":"The purchase receipt could not be verified."}}""",
                status = HttpStatusCode.BadRequest,
            )
        }
        val outcome = repo.redeem("chip_pack_small", transaction)
        assertEquals(RedeemOutcome.RejectedTerminal, outcome)
    }

    @Test
    fun redeem_badRequestOtherCode_isRejectedNotTerminal_soItStaysReplayable() = runUnitTest {
        // A non-terminal 4xx (unknown product / catalog drift): still no credit,
        // but the caller must NOT finish the transaction — a later launch, once
        // the catalog syncs, can still redeem it.
        val repo = buildRepo { _ ->
            respondJson(
                """{"error":{"code":"unknown_product","message":"No chip pack with that product id."}}""",
                status = HttpStatusCode.BadRequest,
            )
        }
        val outcome = repo.redeem("chip_pack_small", transaction)
        assertEquals(RedeemOutcome.Rejected, outcome)
    }

    @Test
    fun redeem_serviceUnavailable_isUnavailable_notTerminal() = runUnitTest {
        // The server reports receipt validation temporarily unavailable (503,
        // validator unconfigured / store API unreachable). This must NOT finish
        // the transaction — the redeemer retries it later.
        val repo = buildRepo { _ ->
            respondJson(
                """{"error":{"code":"receipt_unavailable","message":"Receipt validation is temporarily unavailable."}}""",
                status = HttpStatusCode.ServiceUnavailable,
            )
        }
        val outcome = repo.redeem("chip_pack_small", transaction)
        assertEquals(RedeemOutcome.Unavailable, outcome)
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
