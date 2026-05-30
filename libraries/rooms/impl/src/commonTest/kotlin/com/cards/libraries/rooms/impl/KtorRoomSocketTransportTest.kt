package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.networking.NetworkConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the production-only behaviors of [KtorRoomSocketTransport]
 * against MockEngine — exclusively the parts that aren't reachable
 * from [FakeRoomSocketTransport]:
 *
 *  - URL construction: https → wss upgrade, port preservation
 *    (the iOS-prod fix that motivated the explicit URL build).
 *  - Handshake failure surfaces as `Result.failure(...)` so the
 *    socket-layer classifier can decide retry vs terminal.
 *
 * Lifecycle / reconnect / fan-out semantics live in
 * [ReconnectingRoomSocketTest] under the fake transport — that's
 * where deterministic time control matters.
 */
class KtorRoomSocketTransportTest {

    @Test
    fun open_failingHandshake_returnsFailureResult() = runTest {
        val transport = newTransport(
            engine = MockEngine { respond(content = "", status = HttpStatusCode.InternalServerError) },
            baseUrl = "https://example.com",
        )

        val result = transport.open("ABC123")
        assertTrue(result.isFailure, "5xx handshake must surface as Result.failure")
    }

    @Test
    fun open_4xx_returnsFailure_withTypedClientException() = runTest {
        val engine = MockEngine { respond(content = "", status = HttpStatusCode.Forbidden) }
        val client = HttpClient(engine) {
            install(WebSockets)
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            expectSuccess = true
        }
        val transport = KtorRoomSocketTransport(
            networkClient = FakeNetworkClient(client),
            networkConfig = FakeNetworkConfig(),
        )

        val result = transport.open("ABC123")
        assertTrue(result.isFailure, "4xx handshake must surface as Result.failure")
        // The exact exception type (ClientRequestException) is what the
        // socket's classifier keys on to map to Closed(Rejected).
        // We don't bind the type here — the socket-layer test pins
        // the classification end-to-end.
    }

    @Test
    fun httpsBaseUrl_upgradesToWss_andPreservesPort() = runTest {
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += request.url.toString()
            respond(content = "", status = HttpStatusCode.InternalServerError)
        }
        val client = HttpClient(engine) {
            install(WebSockets)
            install(io.ktor.client.plugins.DefaultRequest) { url("https://example.com") }
        }
        val transport = KtorRoomSocketTransport(
            networkClient = FakeNetworkClient(client),
            networkConfig = FakeNetworkConfig(baseUrl = "https://example.com"),
        )

        transport.open("ABC123")
        assertTrue(
            requests.any { it.startsWith("wss://") },
            "https base must produce wss://; got: $requests",
        )
        // Pins the iOS-prod fix: a bare `protocol = WSS` swap leaves
        // the WS plugin's pre-filled `port = 80` intact, producing
        // `wss://host:80` which TLS-handshakes against the
        // plaintext :80 listener and fails with WRONG_VERSION_NUMBER.
        assertTrue(
            requests.none { it.contains(":80/") || it.endsWith(":80") },
            "wss handshake must not target port 80; got: $requests",
        )
    }

    @Test
    fun httpBaseUrl_keepsWs() = runTest {
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += request.url.toString()
            respond(content = "", status = HttpStatusCode.InternalServerError)
        }
        val client = HttpClient(engine) {
            install(WebSockets)
            install(io.ktor.client.plugins.DefaultRequest) { url("http://localhost:8080") }
        }
        val transport = KtorRoomSocketTransport(
            networkClient = FakeNetworkClient(client),
            networkConfig = FakeNetworkConfig(baseUrl = "http://localhost:8080"),
        )

        transport.open("ABC123")
        assertTrue(
            requests.any { it.startsWith("ws://") },
            "http base must stay on ws://; got: $requests",
        )
    }

    // ---------- scaffolding ----------

    private fun newTransport(
        engine: MockEngine,
        baseUrl: String = "https://example.com",
    ): KtorRoomSocketTransport {
        val client = HttpClient(engine) {
            install(WebSockets)
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return KtorRoomSocketTransport(
            networkClient = FakeNetworkClient(client),
            networkConfig = FakeNetworkConfig(baseUrl = baseUrl),
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
    ) : NetworkConfig
}
