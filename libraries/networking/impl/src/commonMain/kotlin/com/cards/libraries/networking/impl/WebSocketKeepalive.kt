package com.dangerfield.cards.libraries.networking.impl

import io.ktor.client.HttpClientConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Client→server WebSocket ping cadence. Matches the server's own 15s
 * `pingPeriod` (apps/server .../plugins/WebSockets.kt) so either side notices
 * a dead peer within a couple of cadences.
 */
internal val WEB_SOCKET_PING_INTERVAL: Duration = 15.seconds

/**
 * Installs the client WebSockets plugin with a per-engine keepalive strategy.
 *
 * Platform-split because Ktor's plugin-level pinger (`pingIntervalMillis`) is
 * only safe on engines whose raw session can write an outgoing
 * [io.ktor.websocket.Frame.Ping]:
 *
 *  - **Android/OkHttp** — keepalive must be OkHttp's native `pingInterval`.
 *    The plugin-level value is a trap on this engine: it's inert while Ktor
 *    uses `OkHttpWebsocketSession` directly (it already implements
 *    `DefaultWebSocketSession`, so Ktor never starts its own pinger), but the
 *    moment another plugin wraps the raw session — the debug-only Wiretap WS
 *    inspector does exactly that — Ktor falls back to its own pinger and
 *    OkHttp's write loop throws `UnsupportedFrameTypeException` on the first
 *    outgoing ping, tearing down every quiet socket at exactly the ping
 *    interval. That was MP-32: the "lost connection" banner flashing every
 *    15s through MP games on debug builds.
 *  - **iOS/Darwin** — the engine has no native ping scheduler, but its raw
 *    session maps outgoing pings to `NSURLSessionWebSocketTask.sendPing`, so
 *    the plugin-level pinger is the right mechanism there.
 *
 * Regression guard: `SocketKeepaliveTest` in `:apps:integration`.
 */
internal expect fun HttpClientConfig<*>.installWebSocketKeepalive()
