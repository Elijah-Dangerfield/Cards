package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.config.AdminConfig
import com.dangerfield.cards.server.data.InMemoryRoomService
import com.dangerfield.cards.server.domain.OrphanAnonymousSweep
import com.dangerfield.cards.server.domain.SweepResult
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
        val room = rooms.create(host, "Host")
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
        val room = rooms.create(host, "Host")
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
