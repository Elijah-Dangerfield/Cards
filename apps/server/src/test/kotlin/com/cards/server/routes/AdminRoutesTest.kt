package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.config.AdminConfig
import com.dangerfield.cards.server.data.InMemoryRoomService
import com.dangerfield.cards.server.data.createOrFail
import com.dangerfield.cards.server.domain.OrphanAnonymousSweep
import com.dangerfield.cards.server.domain.SweepResult
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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

    // ---------- scaffolding ----------

    private suspend fun withApp(
        rooms: InMemoryRoomService,
        configuredToken: String? = token,
        ttlMinutes: Int = 5,
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

    /** Minute-grained mutable clock — tests step by integer minutes. */
    private class AdvanceableClock(startMs: Long = 1_700_000_000_000) : Clock {
        private var current: Instant = Instant.fromEpochMilliseconds(startMs)
        override fun now(): Instant = current
        fun advance(minutes: Int) {
            current = current + minutes.minutes
        }
    }
}
