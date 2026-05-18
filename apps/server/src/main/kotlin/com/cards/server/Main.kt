package com.dangerfield.cards.server

import com.dangerfield.cards.server.config.ServerConfig
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * Entry point. Parses [ServerConfig] from the environment (OS vars first,
 * then apps/server/.env), hands the config to `Application.module()`
 * via an attribute so plugins / routes can read it without re-parsing.
 */
fun main() {
    val config = ServerConfig.fromEnv()
    embeddedServer(
        factory = Netty,
        host = config.http.host,
        port = config.http.port,
        module = { module(config) },
    ).start(wait = true)
}
