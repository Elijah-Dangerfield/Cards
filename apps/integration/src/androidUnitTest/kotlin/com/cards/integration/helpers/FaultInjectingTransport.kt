package com.cards.integration.helpers

import com.dangerfield.cards.libraries.rooms.impl.RoomSocketSession
import com.dangerfield.cards.libraries.rooms.impl.RoomSocketTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A [RoomSocketTransport] decorator for chaos tests. Wraps the real transport so a
 * test can:
 *
 *  - **drop** the live socket(s) ([dropActiveConnections]) — the wrapped session's
 *    `incoming` throws, which `ReconnectingRoomSocket` classifies as a transport
 *    failure and retries, exactly like a real network blip; and
 *  - **block** reconnects ([blockReconnects]) so a drop stays down long enough for
 *    the server's presence/grace logic (and the other clients) to observe it.
 *
 * Toggle [blockReconnects] back off to let the client recover.
 */
class FaultInjectingTransport(
    private val delegate: RoomSocketTransport,
) : RoomSocketTransport {

    @Volatile
    var blockReconnects: Boolean = false

    private val live = CopyOnWriteArrayList<ControllableSession>()

    override suspend fun open(code: String): Result<RoomSocketSession> {
        if (blockReconnects) return Result.failure(IOException("fault: reconnects blocked"))
        return delegate.open(code).map { session ->
            ControllableSession(session).also { live += it }
        }
    }

    /** Force every live socket to fail mid-stream (a simulated network drop). */
    fun dropActiveConnections() {
        val snapshot = live.toList()
        live.clear()
        snapshot.forEach { it.cut() }
    }

    /** Drop the live socket(s) and keep them down until [blockReconnects] is cleared. */
    fun dropAndBlock() {
        blockReconnects = true
        dropActiveConnections()
    }

    private class ControllableSession(
        private val delegate: RoomSocketSession,
    ) : RoomSocketSession {
        private val cut = CompletableDeferred<Unit>()

        override val incoming: Flow<String> = flow {
            coroutineScope {
                // A child that, the moment the test cuts the socket, tears down
                // the REAL underlying socket (so the server sees the close and
                // flips presence) and then throws — cancelling the collect below
                // and propagating out of the flow, so the client sees a
                // transport-level failure (→ reconnect).
                val cutter = launch {
                    cut.await()
                    runCatching { delegate.close() }
                    throw IOException("fault-injected socket drop")
                }
                delegate.incoming.collect { emit(it) }
                cutter.cancel()
            }
        }

        override suspend fun send(text: String) {
            if (cut.isCompleted) throw IOException("fault: socket was cut")
            delegate.send(text)
        }

        override suspend fun close() = delegate.close()

        fun cut() {
            cut.complete(Unit)
        }
    }
}
