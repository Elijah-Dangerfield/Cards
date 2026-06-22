package com.dangerfield.cards.server.plugins

import com.dangerfield.cards.server.http.ClientContext
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.baggage.Baggage
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.instrumentation.ktor.v3_0.KtorServerTelemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Installs automatic HTTP-server tracing: the OpenTelemetry Ktor plugin
 * opens a span for every inbound request and closes it on response, so
 * ordinary traffic (`/v1/me`, auth, wallet, …) produces traces without
 * any manual [withSpan] calls. The gameplay `withSpan` chain still nests
 * under the per-request span when it runs inside a request scope.
 *
 * Pass the live SDK returned by [installOpenTelemetry] rather than reading
 * [GlobalOpenTelemetry], so the plugin is wired to the same instance even
 * if the global slot was locked to noop before our `set()` ran.
 */
fun Application.installHttpServerTracing(openTelemetry: OpenTelemetry) {
    install(KtorServerTelemetry) {
        setOpenTelemetry(openTelemetry)
        // Pin the correlation ids onto the HTTP root span directly from headers.
        // The root span's parent context is built by the plugin before any
        // request-scoped interceptor runs, so baggage isn't reliably present at
        // root-span creation — the extractor guarantees the most-queried span.
        // Child spans are covered by the baggage path below.
        attributesExtractor {
            onStart {
                request.headers[ClientContext.HEADER_SESSION_ID]?.takeIf { it.isNotBlank() }
                    ?.let { attributes.put(SpanAttrs.SessionIdCorrelation, it) }
                request.headers[ClientContext.HEADER_INSTALL_ID]?.takeIf { it.isNotBlank() }
                    ?.let { attributes.put(SpanAttrs.InstallIdCorrelation, it) }
            }
        }
    }

    // Carry the same ids in OTel Baggage for the rest of the request. Baggage
    // rides the OTel context across suspend boundaries, so every span created
    // during handling — including the gameplay `withSpan` chain — inherits it
    // as parent-context baggage, and [BaggageAttributeSpanProcessor] copies it
    // onto each span. One value (`session_id`) therefore matches the whole
    // trace tree in Tempo, not just the HTTP root. Runs in the Plugins phase,
    // before route handlers, and wraps `proceed()` so the context propagates
    // downstream. Logs get the same ids via CallLogging MDC (Observability.kt).
    intercept(ApplicationCallPipeline.Plugins) {
        val session = call.request.headers[ClientContext.HEADER_SESSION_ID]?.takeIf { it.isNotBlank() }
        val install = call.request.headers[ClientContext.HEADER_INSTALL_ID]?.takeIf { it.isNotBlank() }
        if (session == null && install == null) return@intercept

        var builder = Baggage.current().toBuilder()
        session?.let { builder = builder.put(BAGGAGE_SESSION_ID, it) }
        install?.let { builder = builder.put(BAGGAGE_INSTALL_ID, it) }
        val context = builder.build().storeInContext(Context.current())

        withContext(context.asContextElement()) { proceed() }
    }
}

// Baggage keys — must match BaggageAttributeSpanProcessor's CORRELATION_BAGGAGE_KEYS
// and the SpanAttrs correlation keys so the value lands under one query string.
private const val BAGGAGE_SESSION_ID = "session_id"
private const val BAGGAGE_INSTALL_ID = "install_id"

/**
 * Single tracer name for the whole server. The OTel convention is one
 * tracer per instrumentation library / module — for our purposes "the
 * server" is one library and the span name (`submit_intent`,
 * `engine.apply_intent`, …) is what disambiguates.
 *
 * Resolved through [GlobalOpenTelemetry] so feature code never threads
 * the SDK through constructors. Tests that need to assert on spans
 * either swap the global SDK (via [installOpenTelemetry] in a
 * `testApplication { … }` block) or short-circuit by accepting that
 * spans become no-ops under the default `OpenTelemetry.noop()`.
 */
internal val serverTracer: Tracer
    get() = GlobalOpenTelemetry.getTracer(SERVER_TRACER_NAME)

internal const val SERVER_TRACER_NAME = "com.dangerfield.cards.server"

/**
 * Span attribute keys shared across the gameplay path. Centralised so a
 * rename touches one file and the same attribute reads identically in
 * Tempo / Jaeger / Honeycomb regardless of which span set it.
 */
internal object SpanAttrs {
    val RoomCode: AttributeKey<String> = AttributeKey.stringKey("room.code")
    val UserId: AttributeKey<String> = AttributeKey.stringKey("user.id")

    /**
     * Game-room session (a server-minted [com.dangerfield.cards.server.GameSession]
     * id) — NOT the client app session. Distinct from [SessionIdCorrelation].
     */
    val SessionId: AttributeKey<String> = AttributeKey.stringKey("session.id")

    /**
     * Client app-session correlation id from `X-Session-Id`. Underscore key,
     * deliberately matching the Sentry tag and Loki log field exactly so the
     * same query string works in all three systems. Set on every HTTP span via
     * the attributes extractor in [Application.installHttpServerTracing].
     */
    val SessionIdCorrelation: AttributeKey<String> = AttributeKey.stringKey("session_id")

    /** Per-install id from `X-Install-Id`; matches the Sentry `install_id` tag. */
    val InstallIdCorrelation: AttributeKey<String> = AttributeKey.stringKey("install_id")
    val HandNumber: AttributeKey<Long> = AttributeKey.longKey("hand.number")
    val IntentType: AttributeKey<String> = AttributeKey.stringKey("intent.type")
    val FrameType: AttributeKey<String> = AttributeKey.stringKey("frame.type")
    val ClientNonce: AttributeKey<String> = AttributeKey.stringKey("client.nonce")
    val Accepted: AttributeKey<Boolean> = AttributeKey.booleanKey("intent.accepted")
    val RejectionReason: AttributeKey<String> = AttributeKey.stringKey("intent.rejection_reason")
    val OccupantsCount: AttributeKey<Long> = AttributeKey.longKey("occupants.count")
}

/**
 * Run [block] inside a fresh span. The span is parented to the current
 * OTel `Context` (so calls nested inside another [withSpan] chain into a
 * tree), recorded as `ERROR` on throw, and ended in a `finally`.
 *
 * Uses [asContextElement] so the span propagates across `suspend`
 * boundaries — child `withSpan` calls inside [block] see it as parent
 * without callers having to thread the span explicitly.
 *
 * Cancellation is not recorded as an error: a cancelled suspend isn't a
 * failure mode of the work, it's the caller giving up. We end the span
 * cleanly and re-throw.
 */
internal suspend inline fun <T> withSpan(
    name: String,
    crossinline configure: Span.() -> Unit = {},
    crossinline block: suspend () -> T,
): T {
    val span = serverTracer.spanBuilder(name).startSpan().apply(configure)
    return try {
        withContext(span.asContextElement()) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        span.setStatus(StatusCode.ERROR, t.message ?: t::class.java.simpleName)
        span.recordException(t)
        throw t
    } finally {
        span.end()
    }
}
