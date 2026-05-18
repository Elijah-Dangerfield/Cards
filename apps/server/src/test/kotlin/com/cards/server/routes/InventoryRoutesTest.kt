package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.headers
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for `POST /v1/inventory/sync`. Confirms the V1
 * (pre-auth) contract: every submitted purchase echoes back as Confirmed.
 *
 * When auth lands and real reconciliation is wired up, the assertions
 * here will broaden — but the wire-shape contract (one result per
 * submitted purchase, idempotent, empty-list-valid) stays.
 */
class InventoryRoutesTest {

    @Test
    fun emptyRequest_returnsEmptyResults() = runTest {
        run("""{"purchases":[]}""") { response ->
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.body<InventorySyncResponse>()
            assertTrue(body.results.isEmpty())
        }
    }

    @Test
    fun singlePurchase_returnsOneConfirmedResult() = runTest {
        run(
            """
            {
              "purchases": [
                {
                  "productId": "emote_dance",
                  "purchasedAtEpochMs": 1000,
                  "costChipsAtPurchase": 2500
                }
              ]
            }
            """.trimIndent(),
        ) { response ->
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.body<InventorySyncResponse>()
            assertEquals(1, body.results.size)
            val result = body.results.single()
            assertEquals("emote_dance", result.productId)
            assertEquals(SyncOutcomeDto.Confirmed, result.outcome)
            assertNull(result.chipsToRefund, "Confirmed outcomes carry no refund")
        }
    }

    @Test
    fun multiplePurchases_returnOneResultEach_inSameOrder() = runTest {
        run(
            """
            {
              "purchases": [
                {"productId":"emote_dance","purchasedAtEpochMs":1000,"costChipsAtPurchase":2500},
                {"productId":"emote_tilt","purchasedAtEpochMs":2000,"costChipsAtPurchase":2500},
                {"productId":"table_neon","purchasedAtEpochMs":3000,"costChipsAtPurchase":12000}
              ]
            }
            """.trimIndent(),
        ) { response ->
            val body = response.body<InventorySyncResponse>()
            assertEquals(3, body.results.size)
            assertEquals(
                listOf("emote_dance", "emote_tilt", "table_neon"),
                body.results.map { it.productId },
                "result order matches request order",
            )
            assertTrue(body.results.all { it.outcome == SyncOutcomeDto.Confirmed })
        }
    }

    @Test
    fun schemaVersion_isOne() = runTest {
        run("""{"purchases":[]}""") { response ->
            val body = response.body<InventorySyncResponse>()
            assertEquals(1, body.schemaVersion)
        }
    }

    @Test
    fun idempotent_sameRequestTwiceProducesSameResponse() = runTest {
        val request = """
            {
              "purchases": [
                {"productId":"emote_dance","purchasedAtEpochMs":1000,"costChipsAtPurchase":2500}
              ]
            }
        """.trimIndent()
        run(request) { firstResponse ->
            val first = firstResponse.body<InventorySyncResponse>()
            run(request) { secondResponse ->
                val second = secondResponse.body<InventorySyncResponse>()
                assertEquals(first.results.size, second.results.size)
                assertEquals(first.results.first().productId, second.results.first().productId)
                assertEquals(first.results.first().outcome, second.results.first().outcome)
            }
        }
    }

    @Test
    fun missingPlatformHeader_doesNotRejectRequest() = runTest {
        // Defensive: even though clients always set X-Platform, the route
        // shouldn't 4xx on its absence. Future endpoints may need it; this
        // one doesn't.
        run(
            body = """{"purchases":[]}""",
            platform = null,
        ) { response ->
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    // ---------- Scaffolding ----------

    private suspend fun run(
        body: String,
        platform: String? = "ios",
        assert: suspend (io.ktor.client.statement.HttpResponse) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                routing { inventoryRoutes() }
            }
            val client = createClient {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }
            val response = client.post("/v1/inventory/sync") {
                contentType(ContentType.Application.Json)
                headers {
                    append(HttpHeaders.Accept, "application/json")
                    platform?.let {
                        append(com.dangerfield.cards.server.http.ClientContext.HEADER_PLATFORM, it)
                    }
                }
                setBody(body)
            }
            assert(response)
        }
    }
}
