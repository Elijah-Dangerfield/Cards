package com.dangerfield.cards.server

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

        // Returns a sparse JSON tree keyed by ConfiguredValue.path (e.g. "upgrade.minSupportedVersionCode").
        // Empty object means "use client defaults for everything". The client merges this with QA-menu
        // overrides before resolving each value.
        get("/v1/app-config") {
            call.respond(currentAppConfig())
        }
    }
}

// In-memory config tree for the dev server. Edit, restart, the change is live on the next refresh.
// Production reads this from Postgres later; the wire format stays the same.
private fun currentAppConfig(): JsonObject = JsonObject(
    mapOf(
        "upgrade" to JsonObject(
            mapOf(
                "minSupportedVersionCode" to JsonPrimitive(1),
                "maintenanceMode" to JsonPrimitive("off"),
                "maintenanceMessage" to JsonPrimitive("We're updating the servers, back in a moment."),
            ),
        ),
    ),
)
