package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.gameplay.GameEvent
import io.opentelemetry.api.trace.SpanContext

/**
 * A [GameEvent] paired with the OTel [SpanContext] that was active when
 * it was emitted — the `state_mutate` / `start_hand` span running under
 * the originating `submit_intent`. The room socket publisher attaches
 * this context as a span *link* on the per-recipient `ws_send` span, so
 * an outbound broadcast is causally tied back to the intent that
 * produced it instead of floating as a disconnected root span.
 *
 * The link rides on this envelope rather than on the domain [GameEvent]
 * (or [com.dangerfield.cards.libraries.gameplay.GameState]) so tracing
 * concerns never leak into the gameplay types. When no span is active
 * (SDK unconfigured) the emitter stamps [SpanContext.getInvalid]; the
 * publisher skips invalid contexts rather than emitting a dangling link.
 */
data class TracedGameEvent(
    val event: GameEvent,
    val originSpanContext: SpanContext,
)
