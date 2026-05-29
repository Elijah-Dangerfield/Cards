package com.dangerfield.cards.server.plugins

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

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
    val SessionId: AttributeKey<String> = AttributeKey.stringKey("session.id")
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
