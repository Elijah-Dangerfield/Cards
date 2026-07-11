package com.dangerfield.cards.libraries.networking.impl

import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.client.plugins.websocket.WebSockets
import java.util.concurrent.TimeUnit

internal actual fun HttpClientConfig<*>.installWebSocketKeepalive() {
    // No pingIntervalMillis here — see the expect doc for why that breaks
    // OkHttp the moment anything wraps the raw session.
    install(WebSockets)
    @Suppress("UNCHECKED_CAST")
    (this as HttpClientConfig<OkHttpConfig>).engine {
        config {
            pingInterval(WEB_SOCKET_PING_INTERVAL.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        }
    }
}
