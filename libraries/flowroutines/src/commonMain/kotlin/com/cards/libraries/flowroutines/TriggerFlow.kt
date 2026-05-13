package com.dangerfield.cards.libraries.coreflowroutines

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/**
 * A flow that can be used as a trigger mechanism. Emits [Unit] values that can be triggered
 * programmatically via [pull].
 *
 * This is useful for scenarios where you need to recreate or refresh a flow based on external
 * events (e.g., error recovery, invalidation, manual refresh).
 *
 * @param emitInitially If `true` (default), the flow will emit immediately when collected.
 *                      If `false`, the flow will only emit after [pull] is called at least once.
 *
 * @example
 * ```
 * val trigger = TriggerFlow(emitInitially = true)
 * trigger
 *   .flatMapLatest {
 *     // This block runs initially and again after each pull()
 *     createNewFlow()
 *   }
 *   .collect { ... }
 *
 * // Later, trigger a refresh
 * trigger.pull()
 * ```
 */
class TriggerFlow(
    emitInitially: Boolean = true,
    onTriggered: (Int) -> Unit = {},
) : Flow<Unit> {
    /**
     * Internal counter that increments on each [pull] call.
     * Starts at 0, which allows initial emission when [emitInitially] is true.
     */
    private val triggerCounter = MutableStateFlow(1)

    /**
     * The underlying flow that emits Unit values.
     * Filters based on [emitInitially] to control initial emission behavior.
     */
    private val unitFlow: Flow<Unit> = triggerCounter
        .filter { count ->
            if (emitInitially) {
                count >= 1
            } else {
                count > 1
            }
        }
        .onEach { onTriggered(it) }
        .map { Unit }

    override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<Unit>) {
        unitFlow.collect(collector)
    }

    /**
     * Triggers the flow to emit a new [Unit] value.
     *
     * This method is thread-safe and can be called from any context.
     * Each call increments an internal counter, causing the flow to emit.
     *
     * @see [kotlinx.coroutines.flow.MutableStateFlow.update] for thread-safety guarcardses
     */
    fun pull() {
        triggerCounter.update { it + 1 }
    }
}
