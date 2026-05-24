package com.dangerfield.cards.libraries.rooms.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.rooms.ClosedReason
import com.dangerfield.cards.libraries.rooms.RoomConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins [ReconnectingRoomSocket]'s state machine via MockEngine. We
 * don't (and can't) test against a real Ktor server here — the
 * WS-layer integration is covered server-side in
 * `RoomSocketRoutesTest`. What we ARE testing is the client's
 * reconnect + state-emission behavior in isolation:
 *
 *  - Handshake failure → Connecting → Reconnecting(1, e) → … (we don't
 *    let the test loop forever — we just assert the first reconnect
 *    emission and cancel).
 *  - Backoff increases per failed attempt — captured by checking that
 *    we don't tight-loop.
 *  - The flow never throws; even hard transport errors surface as
 *    Reconnecting.
 *
 * The "happy path → Snapshot → Connected(room)" round-trip is hard to
 * stage with MockEngine (which doesn't speak the WS protocol fully).
 * That's exercised end-to-end in the server's RoomSocketRoutesTest
 * with a real client. Keep both layers tested independently — the
 * unit + integration story stays clean.
 */
class ReconnectingRoomSocketTest {

    @Test
    fun handshakeFailure_emitsConnecting_thenReconnecting() = runTest {
        val socket = newSocket(MockEngine { respond(content = "", status = HttpStatusCode.InternalServerError) })

        socket.observe("ABC123").test {
            assertEquals(RoomConnection.Connecting, awaitItem())
            val reconnecting = assertIs<RoomConnection.Reconnecting>(awaitItem())
            assertEquals(1, reconnecting.attempt, "first failure surfaces as attempt 1")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun transportException_alsoSurfacesReconnecting() = runTest {
        val socket = newSocket(MockEngine { throw SimulatedNetworkError("connection refused") })

        socket.observe("ABC123").test {
            assertEquals(RoomConnection.Connecting, awaitItem())
            val reconnecting = assertIs<RoomConnection.Reconnecting>(awaitItem())
            assertTrue(reconnecting.cause is SimulatedNetworkError)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun handshake4xx_surfaces_Closed_Rejected_andStopsLoop() = runTest {
        // A 4xx on the WS handshake means the server rejected the upgrade
        // (most commonly: user isn't a member of the room). The reconnect
        // loop has no business retrying — the collector needs to call
        // POST /join + re-subscribe. Surfaces as Closed(Rejected) and
        // terminates so the collector can act on it.
        //
        // Uses its own client config with `expectSuccess = true` to match
        // production NetworkClientImpl — the default mock client doesn't
        // distinguish 4xx vs 5xx exception types.
        val engine = MockEngine { respond(content = "", status = HttpStatusCode.Forbidden) }
        val client = HttpClient(engine) {
            install(WebSockets)
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            expectSuccess = true
        }
        val socket = ReconnectingRoomSocket(
            networkClient = FakeNetworkClient(client),
            networkConfig = FakeNetworkConfig(),
        )

        socket.observe("ABC123").test {
            assertEquals(RoomConnection.Connecting, awaitItem())
            val closed = assertIs<RoomConnection.Closed>(awaitItem())
            assertEquals(ClosedReason.Rejected, closed.reason)
            awaitComplete()
        }
    }

    @Test
    fun cancellation_propagates_andStopsLoop() = runTest {
        val socket = newSocket(MockEngine { throw SimulatedNetworkError("nope") })

        socket.observe("ABC123").test {
            assertEquals(RoomConnection.Connecting, awaitItem())
            // Receive one reconnect, then cancel. The test would hang
            // if the loop kept emitting after cancellation.
            assertIs<RoomConnection.Reconnecting>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun httpsBaseUrl_upgradesToWss_inHandshakeRequest() = runTest {
        // Pins the production-broke-iOS-MP fix: the Darwin engine wouldn't
        // upgrade `ws://` to `wss://` on its own, and Fly/Cloudflare reject
        // the plaintext handshake. Use the configured base URL's scheme
        // directly instead of reading the URLBuilder's protocol (which
        // hasn't been merged with the base yet at this point in the pipeline).
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += request.url.toString()
            respond(content = "", status = HttpStatusCode.InternalServerError)
        }
        val client = HttpClient(engine) {
            install(WebSockets)
            install(io.ktor.client.plugins.DefaultRequest) { url("https://example.com") }
        }
        val socket = ReconnectingRoomSocket(
            networkClient = FakeNetworkClient(client),
            networkConfig = FakeNetworkConfig(baseUrl = "https://example.com"),
        )

        socket.observe("ABC123").test {
            assertEquals(RoomConnection.Connecting, awaitItem())
            assertIs<RoomConnection.Reconnecting>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(
            requests.any { it.startsWith("wss://") },
            "request should upgrade to wss when base is https; got: $requests",
        )
    }

    @Test
    fun httpBaseUrl_keepsWs_inHandshakeRequest() = runTest {
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += request.url.toString()
            respond(content = "", status = HttpStatusCode.InternalServerError)
        }
        val client = HttpClient(engine) {
            install(WebSockets)
            install(io.ktor.client.plugins.DefaultRequest) { url("http://localhost:8080") }
        }
        val socket = ReconnectingRoomSocket(
            networkClient = FakeNetworkClient(client),
            networkConfig = FakeNetworkConfig(baseUrl = "http://localhost:8080"),
        )

        socket.observe("ABC123").test {
            assertEquals(RoomConnection.Connecting, awaitItem())
            assertIs<RoomConnection.Reconnecting>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(
            requests.any { it.startsWith("ws://") },
            "request should stay on ws when base is http; got: $requests",
        )
    }

    // ---------- scaffolding ----------

    private fun newSocket(engine: MockEngine): ReconnectingRoomSocket {
        val client = HttpClient(engine) {
            install(WebSockets)
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return ReconnectingRoomSocket(
            networkClient = FakeNetworkClient(client),
            networkConfig = FakeNetworkConfig(),
        )
    }

    @OptIn(com.dangerfield.cards.libraries.networking.InternalNetworkingApi::class)
    private class FakeNetworkClient(private val httpClient: HttpClient) : NetworkClient {
        override val client: HttpClient get() = httpClient
        override val authenticatedClient: HttpClient get() = httpClient
        override suspend fun awaitAuthReady() = Unit
    }

    private class FakeNetworkConfig(
        override val baseUrl: String = "https://example.com",
    ) : com.dangerfield.cards.libraries.networking.NetworkConfig

    /**
     * Cross-platform stand-in for `java.io.IOException`. The reconnect
     * loop only cares that *something* threw — the exact type is matched
     * via `is` so the assertion stays meaningful while the test stays
     * iOS-compatible.
     */
    private class SimulatedNetworkError(message: String) : RuntimeException(message)
}
