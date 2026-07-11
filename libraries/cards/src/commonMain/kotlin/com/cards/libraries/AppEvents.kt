package com.dangerfield.cards.libraries.cards

import kotlinx.coroutines.flow.Flow

/**
 * Directly injectable [Flow] of [AppEvent]s, for consumers that react to
 * lifecycle / identity transitions reactively — filtering, combining, or
 * carrying state across events — rather than implementing [AppEventListener].
 *
 * Same shape as `AppConfigFlow`: a thin wrapper over the bus's stream so callers
 * depend on a `Flow`, not the whole [AppEventBus]. The stream is hot and replays
 * the most recent event to a new collector, so a subscriber that attaches during
 * boot still sees the activation event that fired moments before it.
 */
class AppEvents(
    private val bus: AppEventBus,
    private val backingFlow: Flow<AppEvent> = bus.eventStream(),
) : Flow<AppEvent> by backingFlow {

    /**
     * The replay-free stream — events from now on only. Use for edge
     * semantics (re-fire triggers), where reacting to the replayed
     * pre-subscribe event would double-fire. See [AppEventBus.liveEventStream].
     */
    fun live(): Flow<AppEvent> = bus.liveEventStream()
}
