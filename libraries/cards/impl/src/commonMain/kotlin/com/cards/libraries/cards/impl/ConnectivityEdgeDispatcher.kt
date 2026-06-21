package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventBus
import com.dangerfield.cards.libraries.core.AppState
import com.dangerfield.cards.libraries.core.AutoInit
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Turns the raw [AppState.isOffline] signal into a first-class
 * [AppEvent.ConnectivityRegained] event on the offline→online edge.
 *
 * Kept deliberately separate from [AppEventDispatcher] — which owns boot /
 * foreground / background lifecycle and knows nothing about networking — so the
 * "connectivity is a data-freshness trigger" concern lives in exactly one place.
 *
 * The pipeline:
 *  - `drop(1)` — ignore the **optimistic initial** value the StateFlow replays
 *    on subscribe (so an online cold boot, which starts `isOffline=false`, never
 *    fires a spurious regain). After this drop, every emission is a genuine
 *    *change* from the value at subscription time. (`StateFlow` already only
 *    emits on a real value change, so no `distinctUntilChanged` is needed.)
 *  - `debounce` — absorb connectivity flapping (online↔offline bouncing) so a
 *    flaky link doesn't dispatch a storm of events.
 *  - `filter { !it }` — only the `false` (online) side is a "regained" edge.
 *    Because the stream is distinct and the initial value was dropped, every
 *    `false` here is necessarily preceded by a `true`, i.e. a true→false edge.
 *
 * [AutoInit] so the observer is running before the user can navigate — a regain
 * that arrives during the splash still heals identity / flushes outboxes.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AutoInit::class, multibinding = true)
@Inject
class ConnectivityEdgeDispatcher(
    private val appState: AppState,
    private val appEventBus: AppEventBus,
    appScope: AppCoroutineScope,
) : AutoInit {

    private val logger = KLog.withTag("ConnectivityEdge")

    @OptIn(FlowPreview::class)
    private val started = run {
        logger.d { "init: observing isOffline; ignoring the initial value, edge-only on true→false" }
        appScope.launch {
            appState.isOffline
                .drop(1)
                .debounce(DEBOUNCE_MS)
                .filter { offline -> !offline }
                .collect {
                    logger.i { "Connectivity regained (offline→online) — dispatching ConnectivityRegained" }
                    appEventBus.dispatch(AppEvent.ConnectivityRegained)
                }
        }
    }

    private companion object {
        /**
         * Quiet window a connectivity change must hold before we trust it. Long
         * enough to swallow a flaky link bouncing online↔offline, short enough
         * that a real reconnect heals promptly.
         */
        const val DEBOUNCE_MS: Long = 750L
    }
}
