package com.dangerfield.cards.libraries.networking.impl

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.websocket.WebSockets

internal actual fun HttpClientConfig<*>.installWebSocketKeepalive() {
    install(WebSockets) {
        pingIntervalMillis = WEB_SOCKET_PING_INTERVAL.inWholeMilliseconds
    }
}
