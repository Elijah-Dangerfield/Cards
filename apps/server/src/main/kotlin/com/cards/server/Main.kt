package com.dangerfield.cards.server

import com.dangerfield.cards.libraries.appconfig.AppConfig
import com.dangerfield.cards.libraries.appconfig.MaintenanceState
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

fun main() {
    val port = (System.getenv("PORT")?.toIntOrNull()) ?: 8080
    val host = System.getenv("HOST") ?: "0.0.0.0"
    embeddedServer(Netty, host = host, port = port, module = Application::module).start(wait = true)
}

private fun Application.module() {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            },
        )
    }
    install(CORS) {
        anyHost()
        allowHeader("Content-Type")
        allowHeader("Authorization")
    }
    install(CallLogging) {
        level = Level.INFO
        filter { call -> !call.request.path().startsWith("/_health") }
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "unknown")))
        }
    }

    routing {
        get("/_health") {
            call.respond(mapOf("ok" to true))
        }

        get("/v1/app-config") {
            call.respond(currentAppConfig())
        }
    }
}

// In-memory config for the dev server. Replace with Postgres read in Phase 2 deployment work.
private fun currentAppConfig(): AppConfig = AppConfig(
    minSupportedClientVersionCode = 1,
    maintenance = MaintenanceState.Off,
    startingChipGrant = 10_000,
    anonymousChipGrant = 2_000,
    botXpMultiplier = 0.5,
    turnTimerSecondsDefault = 30,
    emoteCooldownMs = 2_000,
    featureUnlocks = emptyMap(),
)
