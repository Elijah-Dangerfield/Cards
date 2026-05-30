package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.gameplay.GameState
import io.opentelemetry.api.trace.SpanContext

/**
 * A [GameState] paired with the OTel [SpanContext] active when it was
 * published — the `state_mutate` / `start_hand` span running under the
 * originating `submit_intent`. The room socket's game-state publisher
 * attaches this context as a span *link* on the per-recipient
 * `GameStateSnapshot` `ws_send` span, so a broadcast snapshot is tied
 * back to the intent that produced it instead of floating as a root span.
 *
 * Mirrors [TracedGameEvent] for the state-snapshot leg. The link rides on
 * this envelope (a sibling [kotlinx.coroutines.flow.StateFlow], not the
 * domain [GameState]) so tracing concerns never leak into the gameplay
 * types and every existing `state` reader stays untouched.
 *
 * Attribution is *approximate* for this leg: `StateFlow` conflation
 * collapses rapid mutations, so a recipient that attaches mid-burst may
 * observe only the latest state and link to its origin, not every
 * intermediate one. That trade-off is intentional — snapshots are
 * idempotent full-state frames, and the per-intent causal chain is
 * captured precisely on the un-conflated [TracedGameEvent] events leg.
 *
 * When no span is active (SDK unconfigured, or the hydrate path) the
 * publisher stamps [SpanContext.getInvalid]; the fan-out skips invalid
 * contexts rather than emitting a dangling link.
 */
data class TracedState(
    val state: GameState,
    val originSpanContext: SpanContext,
)
