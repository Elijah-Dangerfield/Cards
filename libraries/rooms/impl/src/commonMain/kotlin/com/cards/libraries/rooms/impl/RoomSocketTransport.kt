package com.dangerfield.cards.libraries.rooms.impl

import kotlinx.coroutines.flow.Flow

/**
 * Thin abstraction over the WebSocket layer so [ReconnectingRoomSocket]
 * stays focused on lifecycle, sharing, and reconnect classification —
 * not on Ktor-specific frame plumbing. The production binding is
 * [KtorRoomSocketTransport]; tests inject a fake that exposes the
 * `incoming` / `send` / `close` seams without speaking the actual WS
 * protocol.
 */
interface RoomSocketTransport {
    /**
     * Open a WebSocket session for the given room code. The Result
     * carries the underlying exception on failure so the caller can
     * classify it (4xx → terminal, 5xx / transport → retry) without
     * the transport having to know the wire-state semantics.
     */
    suspend fun open(code: String): Result<RoomSocketSession>
}

/**
 * One live WebSocket. Text frames flow in via [incoming]; outbound
 * goes through [send]; [close] tears it down. Cancellation of the
 * collecting coroutine implicitly tears the underlying socket down on
 * the production impl.
 */
interface RoomSocketSession {
    /**
     * Hot stream of inbound text frames. Completes (without error)
     * when the peer closes cleanly; throws on a transport-level
     * failure mid-stream.
     */
    val incoming: Flow<String>

    suspend fun send(text: String)

    suspend fun close()
}
