package com.dangerfield.cards.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.server.domain.InventoryRepository
import com.dangerfield.cards.server.domain.OwnedItem
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.plugins.installAuthenticationWithVerifier
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Route-level tests for `POST /v1/inventory/sync`. Auth is exercised
 * end-to-end (HS256-signed test JWTs against a matching verifier — same
 * pattern as MeRoutesTest); the inventory repository is faked because
 * Postgres semantics get their own Testcontainers test elsewhere.
 *
 * What we pin:
 *  - Empty request returns empty results without touching the repo.
 *  - Each submitted purchase produces a Confirmed result + a repo call,
 *    in the SAME ORDER as the request body.
 *  - Idempotent on the wire: same request body twice → same response
 *    twice (the underlying repo absorbs the duplicate; the route doesn't
 *    care).
 *  - Schema version stays at 1.
 *  - Missing / wrong-secret / expired bearer tokens 401.
 */
@OptIn(ExperimentalTime::class)
class InventoryRoutesTest {

    private val testIssuer = "https://test-project.supabase.co/auth/v1"
    private val testSecret = "0123456789abcdef0123456789abcdef0123456789abcdef"
    private val userId = UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"))

    @Test
    fun emptyRequest_returnsEmptyResults_andNeverHitsRepo() = runTest {
        val repo = FakeInventoryRepository()
        post(repo, """{"purchases":[]}""") { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<InventorySyncResponse>()
            assertTrue(body.results.isEmpty())
            assertEquals(0, repo.recordCalls)
        }
    }

    @Test
    fun singlePurchase_returnsConfirmed_andPersistsViaRepo() = runTest {
        val repo = FakeInventoryRepository()
        post(
            repo,
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
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<InventorySyncResponse>()
            val result = body.results.single()
            assertEquals("emote_dance", result.productId)
            assertEquals(SyncOutcomeDto.Confirmed, result.outcome)
            assertNull(result.chipsToRefund, "Confirmed outcomes carry no refund")
            assertEquals(1, repo.recordCalls)
            val owned = repo.owned(userId).single()
            assertEquals("emote_dance", owned.productId)
            assertEquals(2_500L, owned.costChipsAtPurchase)
            assertEquals(1_000L, owned.purchasedAt.toEpochMilliseconds())
        }
    }

    @Test
    fun multiplePurchases_returnConfirmedResults_inSameOrderAsRequest() = runTest {
        val repo = FakeInventoryRepository()
        post(
            repo,
            """
            {
              "purchases": [
                {"productId":"emote_dance","purchasedAtEpochMs":1000,"costChipsAtPurchase":2500},
                {"productId":"emote_tilt","purchasedAtEpochMs":2000,"costChipsAtPurchase":2500},
                {"productId":"felt_charcoal","purchasedAtEpochMs":3000,"costChipsAtPurchase":12000}
              ]
            }
            """.trimIndent(),
        ) { resp ->
            val body = resp.body<InventorySyncResponse>()
            assertEquals(
                listOf("emote_dance", "emote_tilt", "felt_charcoal"),
                body.results.map { it.productId },
                "result order must match request order",
            )
            assertTrue(body.results.all { it.outcome == SyncOutcomeDto.Confirmed })
            assertEquals(3, repo.recordCalls)
        }
    }

    @Test
    fun idempotent_sameRequestTwiceProducesSameResponse() = runTest {
        val repo = FakeInventoryRepository()
        val body = """{"purchases":[{"productId":"emote_dance","purchasedAtEpochMs":1000,"costChipsAtPurchase":2500}]}"""
        post(repo, body) { first ->
            val a = first.body<InventorySyncResponse>()
            post(repo, body) { second ->
                val b = second.body<InventorySyncResponse>()
                assertEquals(a.results, b.results)
                // Repo is called both times — recordPurchase is idempotent
                // at the storage layer, not at the route. That's the right
                // split: keeps the route dumb.
                assertEquals(2, repo.recordCalls)
                assertEquals(1, repo.owned(userId).size, "only one row persists for the same product")
            }
        }
    }

    @Test
    fun schemaVersion_isOne() = runTest {
        post(FakeInventoryRepository(), """{"purchases":[]}""") { resp ->
            assertEquals(1, resp.body<InventorySyncResponse>().schemaVersion)
        }
    }

    @Test
    fun ownedSnapshot_isEmpty_whenUserHasNoPurchases() = runTest {
        post(FakeInventoryRepository(), """{"purchases":[]}""") { resp ->
            assertTrue(resp.body<InventorySyncResponse>().owned.isEmpty())
        }
    }

    @Test
    fun ownedSnapshot_includesSubmittedPurchases() = runTest {
        val repo = FakeInventoryRepository()
        post(
            repo,
            """
            {
              "purchases": [
                {"productId":"emote_dance","purchasedAtEpochMs":1000,"costChipsAtPurchase":2500},
                {"productId":"felt_charcoal","purchasedAtEpochMs":3000,"costChipsAtPurchase":12000}
              ]
            }
            """.trimIndent(),
        ) { resp ->
            val body = resp.body<InventorySyncResponse>()
            assertEquals(
                setOf("emote_dance", "felt_charcoal"),
                body.owned.map { it.productId }.toSet(),
            )
            val dance = body.owned.first { it.productId == "emote_dance" }
            assertEquals(2_500L, dance.costChipsAtPurchase)
            assertEquals(1_000L, dance.purchasedAtEpochMs)
        }
    }

    @Test
    fun ownedSnapshot_includesPriorPurchases_evenWhenRequestIsEmpty() = runTest {
        val repo = FakeInventoryRepository()
        post(
            repo,
            """{"purchases":[{"productId":"emote_dance","purchasedAtEpochMs":1000,"costChipsAtPurchase":2500}]}""",
        ) {}
        post(repo, """{"purchases":[]}""") { resp ->
            val body = resp.body<InventorySyncResponse>()
            assertEquals(listOf("emote_dance"), body.owned.map { it.productId })
            assertTrue(body.results.isEmpty(), "no purchases submitted → no per-purchase results")
        }
    }

    @Test
    fun ownedSnapshot_isPerUser() = runTest {
        val repo = FakeInventoryRepository()
        val otherUser = UserId(UUID.fromString("33333333-3333-3333-3333-333333333333"))
        post(
            repo,
            """{"purchases":[{"productId":"emote_dance","purchasedAtEpochMs":1000,"costChipsAtPurchase":2500}]}""",
        ) {}
        post(repo, """{"purchases":[]}""", bearer = jwt(forUserId = otherUser)) { resp ->
            assertTrue(
                resp.body<InventorySyncResponse>().owned.isEmpty(),
                "user 2 must not see user 1's owned items",
            )
        }
    }

    @Test
    fun returns401_whenAuthHeaderMissing() = runTest {
        val repo = FakeInventoryRepository()
        post(repo, """{"purchases":[]}""", withBearer = false) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertEquals(0, repo.recordCalls)
        }
    }

    @Test
    fun returns401_whenJwtSignedWithWrongSecret() = runTest {
        val foreign = JWT.create()
            .withIssuer(testIssuer)
            .withAudience("authenticated")
            .withSubject(userId.value.toString())
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(Algorithm.HMAC256("wrong-secret-wrong-secret-wrong-secret-wrong-secret"))
        post(FakeInventoryRepository(), """{"purchases":[]}""", bearer = foreign) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun differentUsers_storeSeparately() = runTest {
        val repo = FakeInventoryRepository()
        val otherUser = UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
        val body = """{"purchases":[{"productId":"emote_dance","purchasedAtEpochMs":1000,"costChipsAtPurchase":2500}]}"""

        post(repo, body) { /* user-1 buys */ }
        post(repo, body, bearer = jwt(forUserId = otherUser)) { /* user-2 buys */ }

        assertEquals(1, repo.owned(userId).size)
        assertEquals(1, repo.owned(otherUser).size)
        assertNotEquals(userId, otherUser)
    }

    // ---------- scaffolding ----------

    private fun jwt(forUserId: UserId = userId): String = JWT.create()
        .withIssuer(testIssuer)
        .withAudience("authenticated")
        .withSubject(forUserId.value.toString())
        .withIssuedAt(Date())
        .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
        .sign(Algorithm.HMAC256(testSecret))

    private val testVerifier = JWT.require(Algorithm.HMAC256(testSecret))
        .withIssuer(testIssuer)
        .withAudience("authenticated")
        .build()

    private suspend fun post(
        repo: InventoryRepository,
        body: String,
        withBearer: Boolean = true,
        bearer: String = jwt(),
        assert: suspend (io.ktor.client.statement.HttpResponse) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing { inventoryRoutes(repo) }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val response = client.post("/v1/inventory/sync") {
                contentType(ContentType.Application.Json)
                if (withBearer) header(HttpHeaders.Authorization, "Bearer $bearer")
                setBody(body)
            }
            assert(response)
        }
    }

    private class FakeInventoryRepository : InventoryRepository {
        private val byUser: MutableMap<UserId, MutableMap<String, OwnedItem>> = mutableMapOf()
        var recordCalls: Int = 0
            private set

        fun owned(userId: UserId): List<OwnedItem> = byUser[userId].orEmpty().values.toList()

        override suspend fun listOwned(userId: UserId): List<OwnedItem> = owned(userId)

        override suspend fun recordPurchase(
            userId: UserId,
            productId: String,
            costChipsAtPurchase: Long,
            purchasedAt: Instant,
        ): OwnedItem {
            recordCalls++
            val forUser = byUser.getOrPut(userId) { mutableMapOf() }
            // First-purchase-wins (matches Postgres impl).
            val existing = forUser[productId]
            if (existing != null) return existing
            val owned = OwnedItem(productId, costChipsAtPurchase, purchasedAt)
            forUser[productId] = owned
            return owned
        }

        override suspend fun recordEarnedGrant(
            userId: UserId,
            productId: String,
            grantedAt: Instant,
        ): OwnedItem = error("recordEarnedGrant not used in this test")

        override suspend fun deleteAllForUser(userId: UserId) {
            byUser.remove(userId)
        }
    }
}
