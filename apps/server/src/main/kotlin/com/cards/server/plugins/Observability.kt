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
 *
 * Room-route calls additionally carry `room_code`, parsed from the request path
 * (`/v1/rooms/{code}/…`) and uppercased to match the span `room.code` attribute
 * and the room's canonical code. It's emitted as MDC (→ Loki structured
 * metadata), not a stream label — a room code is unbounded high-cardinality, so
 * a Loki label would explode the index; structured metadata makes
 * `{service_name="cards-server"} | room_code="<CODE>"` cheap. This is the
 * load-bearing pivot for multiplayer issues, where one room spans many
 * users/sessions and a long-lived socket coroutine's `session_id` is just
 * whoever currently holds it (a bot has none).
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
        // Room code rides in the path, not a header — extract it from the raw
        // request path (available before routing resolves, unlike
        // `call.parameters["code"]`). Every `/v1/rooms/{code}/…` segment-1 is a
        // code (create is `POST /v1/rooms` with no trailing segment, so it can't
        // false-match). Uppercased to match the handlers + the span `room.code`.
        mdc(MDC_ROOM_CODE) { call -> roomCodeFromPath(call.request.path()) }
    }
}

/**
 * Extract the room code from a request path, uppercased to match the room's
 * canonical code and the span `room.code` attribute, or null when the path
 * isn't a room-scoped route. The first segment after `/v1/rooms/` is always a
 * code (create is `POST /v1/rooms` with no trailing segment, so it can't
 * false-match; `join`/`me`/`bots`/`socket` only appear at segment 2). Reads the
 * raw path rather than `call.parameters["code"]` so it works in the CallLogging
 * MDC provider, which runs before routing resolves path parameters.
 */
internal fun roomCodeFromPath(path: String): String? =
    ROOM_CODE_IN_PATH.find(path)?.groupValues?.get(1)?.uppercase()

// MDC keys mirror the Sentry tags and OTel span attribute keys so a session's
// logs, traces, and crash reports all answer to the same query string.
private const val MDC_SESSION_ID = "session_id"
private const val MDC_INSTALL_ID = "install_id"
private const val MDC_ROOM_CODE = "room_code"

private val ROOM_CODE_IN_PATH = Regex("""/v1/rooms/([^/]+)""")
