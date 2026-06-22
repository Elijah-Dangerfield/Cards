package com.dangerfield.cards.server.plugins

import com.dangerfield.cards.server.http.ClientContext
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.header
import io.ktor.server.request.path
import org.slf4j.event.Level
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Wire request IDs and structured call logging. Every request gets a unique
 * X-Request-Id header that's surfaced in CallLogging's MDC so logs from one
 * request can be grepped together. Health checks are skipped to keep dev logs
 * readable; toggle the filter if you want them in production.
 *
 * The client's `session_id` / `install_id` are also lifted into MDC for the
 * duration of each call. The OTel logback appender forwards MDC entries as log
 * attributes (see logback.xml `captureMdcAttributes`), so every backend log
 * line is filterable in Loki by the same `session_id` the client tags its
 * Sentry events and request spans with — one id, all three systems.
 */
@OptIn(ExperimentalUuidApi::class)
fun Application.installObservability() {
    install(CallId) {
        retrieveFromHeader("X-Request-Id")
        generate { Uuid.random().toString() }
        verify { it.isNotBlank() }
    }
    install(CallLogging) {
        level = Level.INFO
        filter { call -> !call.request.path().startsWith("/_health") }
        mdc(MDC_SESSION_ID) { call ->
            call.request.header(ClientContext.HEADER_SESSION_ID)?.takeIf { it.isNotBlank() }
        }
        mdc(MDC_INSTALL_ID) { call ->
            call.request.header(ClientContext.HEADER_INSTALL_ID)?.takeIf { it.isNotBlank() }
        }
    }
}

// MDC keys mirror the Sentry tags and OTel span attribute keys so a session's
// logs, traces, and crash reports all answer to the same query string.
private const val MDC_SESSION_ID = "session_id"
private const val MDC_INSTALL_ID = "install_id"
