package com.cards.integration.helpers

import com.dangerfield.cards.libraries.core.Catching
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponseContainer
import io.ktor.client.statement.HttpResponsePipeline
import io.ktor.websocket.WebSocketSession

/**
 * Reproduces what the debug-only Wiretap WS inspector does to every WebSocket:
 * intercept at [HttpResponsePipeline.Parse] (before Ktor's WebSockets plugin
 * transforms the session) and swap the engine's raw session for a plain
 * [WebSocketSession] delegator.
 *
 * The wrapper itself is a faithful pass-through — the point is the *type*:
 * OkHttp's raw session implements `DefaultWebSocketSession`, which Ktor's
 * WebSockets plugin adopts as-is; a wrapped session does not, so Ktor builds
 * its own `DefaultWebSocketSession` around it (running the plugin-level
 * pinger, if configured). MP-32 lived in exactly that gap: the plugin-level
 * ping killed every wrapped OkHttp socket at the first ping because OkHttp's
 * write loop can't send a raw `Frame.Ping`.
 *
 * The real Wiretap plugin can't run here (its DI is bootstrapped by an
 * androidx.startup initializer that host-JVM tests don't run — the same
 * reason `installWebSocketInspector` no-ops under JUnit), so this stands in
 * for it. The masking/maxFrameSize guards mirror Wiretap's own
 * `LoggingRawWebSocketSession`, which swallows the setters OkHttp rejects.
 */
fun HttpClient.wiretapAlikeRawSessionWrapper() {
    responsePipeline.intercept(HttpResponsePipeline.Parse) { (info, body) ->
        val raw = body as? WebSocketSession ?: return@intercept
        proceedWith(HttpResponseContainer(info, PassthroughRawSession(raw)))
    }
}

private class PassthroughRawSession(
    private val delegate: WebSocketSession,
) : WebSocketSession by delegate {
    override var masking: Boolean
        get() = Catching { delegate.masking }.getOrDefault(true)
        set(value) {
            Catching { delegate.masking = value }
        }
    override var maxFrameSize: Long
        get() = Catching { delegate.maxFrameSize }.getOrDefault(Long.MAX_VALUE)
        set(value) {
            Catching { delegate.maxFrameSize = value }
        }
}
