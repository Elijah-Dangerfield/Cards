package com.dangerfield.cards.server.plugins

import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS

fun Application.installCors() {
    install(CORS) {
        anyHost()
        allowHeader("Content-Type")
        allowHeader("Authorization")
        allowHeader("X-Request-Id")
        // The config admin web GUI is a browser app on localhost that calls the
        // deployed server cross-origin with these custom headers. Without them in
        // the allow-list the CORS preflight is rejected with 403 before the
        // request is even authenticated. CORS isn't the security boundary here —
        // the admin token is — so allowing the header names is safe.
        allowHeader("X-Admin-Token")
        allowHeader("X-Admin-Actor")
        // Same story for methods: Ktor's CORS plugin only permits GET/POST/HEAD
        // by default, so the admin GUI's flag upserts (PUT) and deletes were
        // rejected at preflight with 403 while GETs sailed through.
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
    }
}
