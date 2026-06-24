package com.cards.integration.helpers

import com.dangerfield.cards.libraries.rooms.impl.RoomSocketSession
import com.dangerfield.cards.libraries.rooms.impl.RoomSocketTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A [RoomSocketTransport] decorator that adds a fixed one-way delay to every
 * outbound send and every inbound frame, simulating a high-RTT network (a
 * submit's round-trip then costs ~2x [oneWayDelayMs]). The wrapped socket is
 * the real one, so the server still processes everything in production order —
 * only the wire is slow.
 *
 * Use it to prove time-sensitive client behaviour holds under latency: ack
 * waits don't spuriously time out, the double-submit dedupe guard still
 * dedupes when the resend overlaps the slow first send, and snapshots arrive
 * in order just later.
 */
class LatencyTransport(
    private val delegate: RoomSocketTransport,
    private val oneWayDelayMs: Long,
) : RoomSocketTransport {

    override suspend fun open(code: String): Result<RoomSocketSession> =
        delegate.open(code).map { DelayedSession(it) }

    private inner class DelayedSession(
        private val delegate: RoomSocketSession,
    ) : RoomSocketSession {

        override val incoming: Flow<String> = flow {
            delegate.incoming.collect { frame ->
                delay(oneWayDelayMs)
                emit(frame)
            }
        }

        override suspend fun send(text: String) {
            delay(oneWayDelayMs)
            delegate.send(text)
        }

        override suspend fun close() = delegate.close()
    }
}
