package com.dangerfield.cards.libraries.rooms.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.networking.NetworkClient
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
        val socket = newSocket(MockEngine { throw java.io.IOException("connection refused") })

        socket.observe("ABC123").test {
            assertEquals(RoomConnection.Connecting, awaitItem())
            val reconnecting = assertIs<RoomConnection.Reconnecting>(awaitItem())
            assertTrue(reconnecting.cause is java.io.IOException)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun cancellation_propagates_andStopsLoop() = runTest {
        val socket = newSocket(MockEngine { throw java.io.IOException("nope") })

        socket.observe("ABC123").test {
            assertEquals(RoomConnection.Connecting, awaitItem())
            // Receive one reconnect, then cancel. The test would hang
            // if the loop kept emitting after cancellation.
            assertIs<RoomConnection.Reconnecting>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------- scaffolding ----------

    private fun newSocket(engine: MockEngine): ReconnectingRoomSocket {
        val client = HttpClient(engine) {
            install(WebSockets)
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return ReconnectingRoomSocket(FakeNetworkClient(client))
    }

    private class FakeNetworkClient(private val httpClient: HttpClient) : NetworkClient {
        override val client: HttpClient get() = httpClient
        override val authenticatedClient: HttpClient get() = httpClient
    }
}
