package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.networking.NetworkConfig
import com.dangerfield.cards.libraries.networking.authedWebSocketSession
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.path
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Production [RoomSocketTransport] backed by Ktor's WebSocket plugin.
 * Owns the URL building (https → wss, port preservation — see comment
 * on [socketRequest]) and the Frame ↔ String mapping. Stays oblivious
 * to room semantics or sharing lifecycle.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class KtorRoomSocketTransport @Inject constructor(
    private val networkClient: NetworkClient,
    private val networkConfig: NetworkConfig,
) : RoomSocketTransport {

    override suspend fun open(code: String): Result<RoomSocketSession> = try {
        networkClient.authedWebSocketSession("rooms.socket") {
            socketRequest(code)
        }.map { KtorRoomSocketSession(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    private fun HttpRequestBuilder.socketRequest(code: String) {
        // Auth bearer rides on the authenticated client's Auth plugin —
        // no need to set Authorization here.
        //
        // We set protocol/host/port explicitly from the parsed base URL
        // instead of leaning on DefaultRequest's merge. Two reasons:
        //  1. The Ktor WebSockets plugin pre-fills the URLBuilder with
        //     `protocol = WS, port = 80` before this block runs. If we
        //     only flip the protocol to WSS, port 80 sticks and Ktor
        //     attempts TLS against the Fly server's plaintext :80 — the
        //     handshake fails with `WRONG_VERSION_NUMBER`.
        //  2. DefaultRequest's URL merge copies host from the base URL
        //     only when the request host is empty, and it leaves any
        //     already-set port alone. So we have to populate both
        //     ourselves to override the WS-plugin defaults.
        val base = Url(networkConfig.baseUrl)
        val useWss = base.protocol.name.equals("https", ignoreCase = true)
        url {
            protocol = if (useWss) URLProtocol.WSS else URLProtocol.WS
            host = base.host
            port = base.port
            path("v1", "rooms", code.uppercase(), "socket")
        }
        method = HttpMethod.Get
    }
}

private class KtorRoomSocketSession(
    private val session: DefaultClientWebSocketSession,
) : RoomSocketSession {

    override val incoming: Flow<String> = flow {
        for (frame in session.incoming) {
            if (frame is Frame.Text) emit(frame.readText())
        }
    }

    override suspend fun send(text: String) {
        session.send(Frame.Text(text))
    }

    override suspend fun close() {
        Catching {
            session.close(CloseReason(CloseReason.Codes.NORMAL, "client-closing"))
        }
    }
}
