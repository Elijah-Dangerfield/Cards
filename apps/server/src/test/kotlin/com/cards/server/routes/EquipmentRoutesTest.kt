package com.dangerfield.cards.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.server.domain.EquipmentRepository
import com.dangerfield.cards.server.domain.EquippedItem
import com.dangerfield.cards.server.domain.UserId
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
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * In-memory fake — lets the route tests focus on HTTP+auth concerns
 * without spinning up Testcontainers. The PostgresEquipmentRepository's
 * last-write-wins semantics get their own DB-backed test elsewhere.
 */
@OptIn(ExperimentalTime::class)
class EquipmentRoutesTest {

    private val testIssuer = "https://test-project.supabase.co/auth/v1"
    private val testSecret = "0123456789abcdef0123456789abcdef0123456789abcdef"
    private val userId = UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"))

    @Test
    fun emptyOps_returnsCurrentEquipped_withoutMutating() = runTest {
        val repo = FakeEquipmentRepository(
            initial = mutableMapOf(
                userId to mutableMapOf(
                    "felt_royal_red" to Instant.fromEpochMilliseconds(1_700_000_001_000),
                ),
            ),
        )
        post(repo, EquipmentSyncRequest(ops = emptyList())) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<EquipmentSyncResponse>()
            assertEquals(1, body.equipped.size)
            assertEquals("felt_royal_red", body.equipped[0].productId)
            assertEquals(0, repo.writeCalls, "empty ops must not write")
        }
    }

    @Test
    fun equipOp_addsRow_andEchoesEquippedSet() = runTest {
        val repo = FakeEquipmentRepository()
        val now = 1_700_000_000_000
        post(
            repo,
            EquipmentSyncRequest(
                ops = listOf(EquipmentOpDto("cardback_marble", equip = true, updatedAtEpochMs = now)),
            ),
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<EquipmentSyncResponse>()
            assertEquals(1, body.equipped.size)
            assertEquals("cardback_marble", body.equipped[0].productId)
        }
    }

    @Test
    fun unequipOp_removesRow_whenOpIsNewerThanExisting() = runTest {
        val repo = FakeEquipmentRepository(
            initial = mutableMapOf(
                userId to mutableMapOf("cardback_marble" to Instant.fromEpochMilliseconds(1_000)),
            ),
        )
        post(
            repo,
            EquipmentSyncRequest(
                ops = listOf(EquipmentOpDto("cardback_marble", equip = false, updatedAtEpochMs = 2_000)),
            ),
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue(resp.body<EquipmentSyncResponse>().equipped.isEmpty())
        }
    }

    @Test
    fun lastWriteWins_appliesOpsInTimestampOrder() = runTest {
        val repo = FakeEquipmentRepository()
        // Submitted out-of-order: unequip @100 then equip @50.
        // Expected: server orders them, processes equip(50) first then
        // unequip(100), final state = unequipped (empty).
        post(
            repo,
            EquipmentSyncRequest(
                ops = listOf(
                    EquipmentOpDto("emotes_drama", equip = false, updatedAtEpochMs = 100),
                    EquipmentOpDto("emotes_drama", equip = true, updatedAtEpochMs = 50),
                ),
            ),
        ) { resp ->
            assertTrue(resp.body<EquipmentSyncResponse>().equipped.isEmpty())
        }
    }

    @Test
    fun returns401_whenAuthHeaderMissing() = runTest {
        val repo = FakeEquipmentRepository()
        post(repo, EquipmentSyncRequest(), withBearer = false) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
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

    private suspend fun post(
        repo: EquipmentRepository,
        body: EquipmentSyncRequest,
        withBearer: Boolean = true,
        assert: suspend (io.ktor.client.statement.HttpResponse) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installRateLimits()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing { equipmentRoutes(repo) }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val response = client.post("/v1/equipment/sync") {
                contentType(ContentType.Application.Json)
                if (withBearer) header(HttpHeaders.Authorization, "Bearer ${validJwt()}")
                setBody(body)
            }
            assert(response)
        }
    }

    private class FakeEquipmentRepository(
        private val initial: MutableMap<UserId, MutableMap<String, Instant>> = mutableMapOf(),
    ) : EquipmentRepository {
        var writeCalls: Int = 0
            private set

        override suspend fun listEquipped(userId: UserId): List<EquippedItem> =
            initial[userId].orEmpty().map { (productId, updatedAt) ->
                EquippedItem(productId = productId, updatedAt = updatedAt)
            }

        override suspend fun equip(
            userId: UserId,
            productId: String,
            newUpdatedAt: Instant,
        ): EquippedItem {
            writeCalls++
            val forUser = initial.getOrPut(userId) { mutableMapOf() }
            val existing = forUser[productId]
            if (existing == null || existing < newUpdatedAt) {
                forUser[productId] = newUpdatedAt
            }
            return EquippedItem(productId, forUser[productId]!!)
        }

        override suspend fun unequip(
            userId: UserId,
            productId: String,
            opUpdatedAt: Instant,
        ): EquippedItem? {
            writeCalls++
            val forUser = initial[userId] ?: return null
            val existing = forUser[productId] ?: return null
            if (existing > opUpdatedAt) {
                return EquippedItem(productId, existing)
            }
            forUser.remove(productId)
            return null
        }
    }
}
