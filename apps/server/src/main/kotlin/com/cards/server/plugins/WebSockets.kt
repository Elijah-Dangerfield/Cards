package com.dangerfield.cards.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.websocket.WebSocketDeflateExtension
import kotlin.time.Duration.Companion.seconds

/**
 * Installs the Ktor WebSocket plugin with sensible per-room defaults.
 * Lives in its own file so changing the heartbeat strategy is a
 * single-file edit + the routes don't need to know.
 *
 * Ping cadence (15s) + timeout (30s) means a peer that's gone silent
 * for half a minute gets its session torn down, which fires our
 * onClose handler in the room socket route and flips that member's
 * isConnected = false. The grace period for "they might reconnect"
 * is implicit — we don't immediately leave the room on a disconnect.
 *
 * Permessage-deflate compresses anything > 4KB. Room snapshots fit
 * comfortably under that today; the option's here for future
 * server-authoritative gameplay-state payloads.
 */
fun Application.installWebSockets() {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
        extensions {
            install(WebSocketDeflateExtension)
        }
    }
}
