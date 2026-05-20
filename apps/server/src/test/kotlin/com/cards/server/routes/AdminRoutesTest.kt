package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.config.AdminConfig
import com.dangerfield.cards.server.data.InMemoryRoomService
import com.dangerfield.cards.server.data.createOrFail
import com.dangerfield.cards.server.domain.ApplyOutcome
import com.dangerfield.cards.server.domain.OrphanAnonymousSweep
import com.dangerfield.cards.server.domain.SweepResult
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.Wallet
import com.dangerfield.cards.server.domain.WalletEvent
import com.dangerfield.cards.server.domain.WalletRepository
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Pins admin endpoint auth + the room-sweep handoff. The orphan-anon
 * sweep is exercised through its existing service-level tests; what's
 * load-bearing here is the new `sweep-disconnected-room-members` route:
 * is it gated by the token, does it pass the configured TTL through to
 * the service, does it return the counts the cron caller depends on.
 *
 * What we pin:
 *  - 401 when no admin token header is present
 *  - 401 when the token is wrong
 *  - 401 when the server has no token configured (would otherwise be
 *    a footgun — a deploy without ADMIN_API_TOKEN should fail closed,
 *    not let any caller trigger sweeps)
 *  - 200 with sweep counts on the happy path
 *  - The TTL the route uses matches AdminConfig (boundary check: a
 *    7-min disconnect with a 5-min TTL gets reaped; a 3-min one doesn't)
 *  - GET /v1/admin/rooms — token-gated like the sweeps, returns one
 *    summary per live room with connected / disconnected counts.
 */
@OptIn(ExperimentalTime::class)
class AdminRoutesTest {

    private val token = "test-admin-token-32-chars-long-x"
    private val host = UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val alice = UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun sweepRoomMembers_returnsUnauthorized_withoutToken() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms) { client ->
            val resp = client.post("/v1/admin/sweep-disconnected-room-members")
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun sweepRoomMembers_returnsUnauthorized_withWrongToken() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms) { client ->
            val resp = client.post("/v1/admin/sweep-disconnected-room-members") {
                header("X-Admin-Token", "not-the-real-token")
            }
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun sweepRoomMembers_returnsUnauthorized_whenServerHasNoToken() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms, configuredToken = null) { client ->
            val resp = client.post("/v1/admin/sweep-disconnected-room-members") {
                header("X-Admin-Token", "anything")
            }
            assertEquals(
                HttpStatusCode.Unauthorized,
                resp.status,
                "unset ADMIN_API_TOKEN must fail closed, not permit anonymous sweeps",
            )
        }
    }

    @Test
    fun sweepRoomMembers_returnsCounts_onHappyPath() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        // Seed: two rooms, one with a sweepable member.
        val room = rooms.createOrFail(host, "Host")
        rooms.markConnected(room.code, host, connected = true)
        rooms.join(room.code, alice, "Alice")
        rooms.markConnected(room.code, alice, connected = false)
        clock.advance(10)

        withApp(rooms) { client ->
            val resp = client.post("/v1/admin/sweep-disconnected-room-members") {
                header("X-Admin-Token", token)
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(1, body["membersReaped"]!!.jsonPrimitive.content.toInt())
            assertEquals(0, body["roomsReaped"]!!.jsonPrimitive.content.toInt())
            assertEquals(1, body["roomsSeen"]!!.jsonPrimitive.content.toInt())
        }
    }

    @Test
    fun sweepRoomMembers_respectsConfiguredTtl_boundary() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val room = rooms.createOrFail(host, "Host")
        rooms.markConnected(room.code, host, connected = true)
        rooms.join(room.code, alice, "Alice")
        rooms.markConnected(room.code, alice, connected = false)

        // 3 minutes of disconnect — should not reap with a 5-min TTL.
        clock.advance(3)
        withApp(rooms, ttlMinutes = 5) { client ->
            val resp = client.post("/v1/admin/sweep-disconnected-room-members") {
                header("X-Admin-Token", token)
            }
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(0, body["membersReaped"]!!.jsonPrimitive.content.toInt())
        }

        // 4 more minutes (7 total) — past the 5-min TTL, reap.
        clock.advance(4)
        withApp(rooms, ttlMinutes = 5) { client ->
            val resp = client.post("/v1/admin/sweep-disconnected-room-members") {
                header("X-Admin-Token", token)
            }
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(1, body["membersReaped"]!!.jsonPrimitive.content.toInt())
        }
    }

    // ---------- GET /v1/admin/rooms ----------

    @Test
    fun listRooms_returnsUnauthorized_withoutToken() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms) { client ->
            val resp = client.get("/v1/admin/rooms")
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun listRooms_returnsUnauthorized_whenServerHasNoToken() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms, configuredToken = null) { client ->
            val resp = client.get("/v1/admin/rooms") {
                header("X-Admin-Token", "anything")
            }
            assertEquals(
                HttpStatusCode.Unauthorized,
                resp.status,
                "unset ADMIN_API_TOKEN must fail closed on the list endpoint too",
            )
        }
    }

    @Test
    fun listRooms_returnsEmpty_whenNoRooms() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms) { client ->
            val resp = client.get("/v1/admin/rooms") {
                header("X-Admin-Token", token)
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(0, body["rooms"]!!.jsonArray.size)
        }
    }

    @Test
    fun listRooms_summarizesEachRoom_withCounts() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val r1 = rooms.createOrFail(host, "Host")
        rooms.markConnected(r1.code, host, connected = true)
        rooms.join(r1.code, alice, "Alice")
        rooms.markConnected(r1.code, alice, connected = false)
        // Second room with a different host.
        val r2 = rooms.createOrFail(alice, "Alice", maxSeats = 4)
        rooms.markConnected(r2.code, alice, connected = true)

        withApp(rooms) { client ->
            val resp = client.get("/v1/admin/rooms") {
                header("X-Admin-Token", token)
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val list = body["rooms"]!!.jsonArray.map { it.jsonObject }
            assertEquals(2, list.size)
            // Find r1 in the list — order is by createdAt then code; both
            // rooms share createdAt in this test so we look up by code.
            val r1Summary = list.first { it["code"]!!.jsonPrimitive.content == r1.code }
            assertEquals(2, r1Summary["memberCount"]!!.jsonPrimitive.content.toInt())
            assertEquals(1, r1Summary["connectedCount"]!!.jsonPrimitive.content.toInt())
            assertEquals(1, r1Summary["disconnectedCount"]!!.jsonPrimitive.content.toInt())
            assertEquals("Lobby", r1Summary["status"]!!.jsonPrimitive.content)
            assertEquals(host.value.toString(), r1Summary["hostUserId"]!!.jsonPrimitive.content)

            val r2Summary = list.first { it["code"]!!.jsonPrimitive.content == r2.code }
            assertEquals(1, r2Summary["memberCount"]!!.jsonPrimitive.content.toInt())
            assertEquals(1, r2Summary["connectedCount"]!!.jsonPrimitive.content.toInt())
            assertEquals(4, r2Summary["maxSeats"]!!.jsonPrimitive.content.toInt())
        }
    }

    // ---------- POST /v1/admin/grant-chips ----------

    @Test
    fun grantChips_returnsUnauthorized_withoutToken() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms) { client ->
            val resp = client.post("/v1/admin/grant-chips") {
                contentType(ContentType.Application.Json)
                setBody("""{"userId":"$alice","delta":500,"reason":"support"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun grantChips_returnsUnauthorized_whenServerHasNoToken() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms, configuredToken = null) { client ->
            val resp = client.post("/v1/admin/grant-chips") {
                header("X-Admin-Token", "anything")
                contentType(ContentType.Application.Json)
                setBody("""{"userId":"$alice","delta":500,"reason":"support"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun grantChips_credits_walletAndStampsLedgerWithAdminGrantReason() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val wallets = FakeWalletRepository()
        withApp(rooms, wallets = wallets) { client ->
            val resp = client.post("/v1/admin/grant-chips") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody("""{"userId":"$alice","delta":750,"reason":"chargeback refund"}""")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("Applied", body["outcome"]!!.jsonPrimitive.content)
            assertEquals(Wallet.STARTER_GRANT + 750, body["balance"]!!.jsonPrimitive.content.toLong())
            assertEquals(1, wallets.applyCalls.size)
            val applied = wallets.applyCalls.single()
            assertEquals(alice, applied.userId)
            assertEquals(750L, applied.delta)
            assertEquals("admin_grant:chargeback refund", applied.reason)
        }
    }

    @Test
    fun grantChips_acceptsNegativeDelta_andReturnsConflictWhenInsufficient() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val wallets = FakeWalletRepository()
        withApp(rooms, wallets = wallets) { client ->
            val resp = client.post("/v1/admin/grant-chips") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody("""{"userId":"$alice","delta":-99999999,"reason":"clawback"}""")
            }
            assertEquals(HttpStatusCode.Conflict, resp.status)
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("InsufficientChips", body["outcome"]!!.jsonPrimitive.content)
            assertEquals(Wallet.STARTER_GRANT, body["balance"]!!.jsonPrimitive.content.toLong())
        }
    }

    @Test
    fun grantChips_passesIdempotencyKeyThrough_andEchoesBack() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val wallets = FakeWalletRepository()
        val key = "support-ticket-1234"
        withApp(rooms, wallets = wallets) { client ->
            val resp = client.post("/v1/admin/grant-chips") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"userId":"$alice","delta":250,"reason":"refund","idempotencyKey":"$key"}""",
                )
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(key, body["idempotencyKey"]!!.jsonPrimitive.content)
            assertEquals(key, wallets.applyCalls.single().idempotencyKey)
        }
    }

    @Test
    fun grantChips_generatesIdempotencyKey_whenAbsent() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val wallets = FakeWalletRepository()
        withApp(rooms, wallets = wallets) { client ->
            val resp = client.post("/v1/admin/grant-chips") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody("""{"userId":"$alice","delta":250,"reason":"refund"}""")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val echoed = body["idempotencyKey"]!!.jsonPrimitive.content
            assertTrue(echoed.isNotBlank(), "server should fill an idempotency key when caller omits one")
            assertEquals(echoed, wallets.applyCalls.single().idempotencyKey)
        }
    }

    @Test
    fun grantChips_replayedKey_returnsAlreadyApplied() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val wallets = FakeWalletRepository()
        val key = "support-ticket-1234"
        withApp(rooms, wallets = wallets) { client ->
            val first = client.post("/v1/admin/grant-chips") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"userId":"$alice","delta":300,"reason":"refund","idempotencyKey":"$key"}""",
                )
            }
            assertEquals(HttpStatusCode.OK, first.status)
            val firstBalance = json.parseToJsonElement(first.bodyAsText())
                .jsonObject["balance"]!!.jsonPrimitive.content.toLong()

            val second = client.post("/v1/admin/grant-chips") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"userId":"$alice","delta":300,"reason":"refund","idempotencyKey":"$key"}""",
                )
            }
            assertEquals(HttpStatusCode.OK, second.status)
            val body = json.parseToJsonElement(second.bodyAsText()).jsonObject
            assertEquals("AlreadyApplied", body["outcome"]!!.jsonPrimitive.content)
            assertEquals(firstBalance, body["balance"]!!.jsonPrimitive.content.toLong())
        }
    }

    @Test
    fun grantChips_returnsBadRequest_onInvalidUserId() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val wallets = FakeWalletRepository()
        withApp(rooms, wallets = wallets) { client ->
            val resp = client.post("/v1/admin/grant-chips") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody("""{"userId":"not-a-uuid","delta":250,"reason":"refund"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals(0, wallets.applyCalls.size)
        }
    }

    @Test
    fun grantChips_returnsBadRequest_onZeroDelta() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val wallets = FakeWalletRepository()
        withApp(rooms, wallets = wallets) { client ->
            val resp = client.post("/v1/admin/grant-chips") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody("""{"userId":"$alice","delta":0,"reason":"refund"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals(0, wallets.applyCalls.size)
        }
    }

    @Test
    fun grantChips_returnsBadRequest_onBlankReason() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val wallets = FakeWalletRepository()
        withApp(rooms, wallets = wallets) { client ->
            val resp = client.post("/v1/admin/grant-chips") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody("""{"userId":"$alice","delta":100,"reason":"   "}""")
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals(0, wallets.applyCalls.size)
        }
    }

    @Test
    fun grantChips_generatedKeysVary_acrossCallsThatOmitTheKey() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val wallets = FakeWalletRepository()
        withApp(rooms, wallets = wallets) { client ->
            repeat(2) {
                client.post("/v1/admin/grant-chips") {
                    header("X-Admin-Token", token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"userId":"$alice","delta":50,"reason":"refund"}""")
                }
            }
            assertEquals(2, wallets.applyCalls.size)
            assertNotEquals(
                wallets.applyCalls[0].idempotencyKey,
                wallets.applyCalls[1].idempotencyKey,
                "two omitted-key calls must produce distinct server-generated keys",
            )
        }
    }

    // ---------- scaffolding ----------

    private suspend fun withApp(
        rooms: InMemoryRoomService,
        configuredToken: String? = token,
        ttlMinutes: Int = 5,
        wallets: WalletRepository = FakeWalletRepository(),
        block: suspend (io.ktor.client.HttpClient) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                routing {
                    adminRoutes(
                        config = AdminConfig(
                            apiToken = configuredToken,
                            orphanAnonTtlDays = 30,
                            disconnectedRoomMemberTtlMinutes = ttlMinutes,
                        ),
                        sweep = NoopSweep,
                        rooms = rooms,
                        wallets = wallets,
                    )
                }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            block(client)
        }
    }

    private object NoopSweep : OrphanAnonymousSweep {
        override suspend fun run(maxInactiveAge: Duration): SweepResult =
            SweepResult(candidatesFound = 0, deleted = 0, failedToDelete = 0, notConfigured = false)
    }

    private data class AppliedCall(
        val userId: UserId,
        val idempotencyKey: String,
        val delta: Long,
        val reason: String,
    )

    /**
     * In-memory wallet impl matching the production semantics: lazy
     * create with [Wallet.STARTER_GRANT], non-negative balance invariant,
     * replay-detection on the per-user idempotency key. Exposes
     * [applyCalls] so tests can inspect what was forwarded.
     */
    private class FakeWalletRepository : WalletRepository {
        private val balances = mutableMapOf<UserId, Long>()
        private val keys = mutableMapOf<UserId, MutableSet<String>>()
        val applyCalls: MutableList<AppliedCall> = mutableListOf()

        override suspend fun findOrCreate(userId: UserId): Wallet {
            val balance = balances.getOrPut(userId) { Wallet.STARTER_GRANT }
            return Wallet(
                userId = userId,
                balance = balance,
                createdAt = Instant.fromEpochMilliseconds(0),
                updatedAt = Instant.fromEpochMilliseconds(0),
            )
        }

        override suspend fun find(userId: UserId): Wallet? = balances[userId]?.let {
            Wallet(
                userId = userId,
                balance = it,
                createdAt = Instant.fromEpochMilliseconds(0),
                updatedAt = Instant.fromEpochMilliseconds(0),
            )
        }

        override suspend fun apply(
            userId: UserId,
            idempotencyKey: String,
            delta: Long,
            reason: String,
        ): ApplyOutcome {
            applyCalls += AppliedCall(userId, idempotencyKey, delta, reason)
            val current = balances.getOrPut(userId) { Wallet.STARTER_GRANT }
            val seen = keys.getOrPut(userId) { mutableSetOf() }
            if (idempotencyKey in seen) {
                return ApplyOutcome.Applied(balance = current, wasAlreadyApplied = true)
            }
            val next = current + delta
            if (next < 0) return ApplyOutcome.InsufficientChips(balance = current)
            balances[userId] = next
            seen += idempotencyKey
            return ApplyOutcome.Applied(balance = next, wasAlreadyApplied = false)
        }

        override suspend fun recentEvents(userId: UserId, limit: Int): List<WalletEvent> = emptyList()
        override suspend fun deleteAllForUser(userId: UserId) {
            balances.remove(userId)
            keys.remove(userId)
        }
    }

    /** Minute-grained mutable clock — tests step by integer minutes. */
    private class AdvanceableClock(startMs: Long = 1_700_000_000_000) : Clock {
        private var current: Instant = Instant.fromEpochMilliseconds(startMs)
        override fun now(): Instant = current
        fun advance(minutes: Int) {
            current = current + minutes.minutes
        }
    }
}
