package com.dangerfield.cards.features.room.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Records the VM's one-off [PlayPokerEvent]s (haptics, sounds, room-closed) so
 * scenarios can assert "the showdown played a sound" / "winning fired a haptic".
 *
 * Attach into a long-lived scope (e.g. `TestScope.backgroundScope`) right after
 * the VM is built, before draining the scheduler, so no early events are missed.
 * Mirrors the manual `launch { vm.eventFlow.collect { ... } }` pattern already
 * used in the MP integration test — no Turbine dependency.
 */
class EventRecorder {

    val events: MutableList<PlayPokerEvent> = mutableListOf()

    fun attach(scope: CoroutineScope, vm: PlayPokerViewModel) {
        scope.launch { vm.eventFlow.collect { events += it } }
    }

    fun haptics(): List<HapticKind> =
        events.filterIsInstance<PlayPokerEvent.PlayHaptic>().map { it.kind }

    fun sounds(): List<SoundKind> =
        events.filterIsInstance<PlayPokerEvent.PlaySound>().map { it.kind }
}
