package com.dangerfield.cards.libraries.telemetry.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.logEvent
import com.dangerfield.cards.libraries.networking.SessionIdProvider
import kotlin.time.Clock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Reports how the previous run ended and keeps the current run's marker
 * current — the per-run foreground-termination signal ENG-42 needs to turn
 * "an iPad died twice on the welcome screen" into a rate.
 *
 * Rides the lifecycle bus rather than DI init for the same reason
 * [AppLaunchedEmitter] does (ENG-24): the session tracker rolls session #1 on
 * the cold-boot foreground, so a marker written at init would be stamped with
 * the pre-boot sentinel uuid and the join back into that run's events would go
 * nowhere.
 *
 * The previous run is consumed before the current one is marked, since the two
 * share one storage slot.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AppEventListener::class, multibinding = true)
@Inject
class RunOutcomeReporter(
    store: RunMarkerStore,
    private val sessionIdProvider: SessionIdProvider,
    clock: Clock,
) : AppEventListener {

    private val tracker = RunOutcomeTracker(store, clock)

    override fun onForeground(event: AppEvent.OnForeground) {
        if (event.isColdBoot) {
            logPreviousRun(tracker.consumePreviousRun())
        }
        tracker.mark(sessionIdProvider.current(), RunState.Foreground)
    }

    override fun onBackground(event: AppEvent.OnBackground) {
        tracker.mark(sessionIdProvider.current(), RunState.Background)
    }
}

/**
 * `app.previous_run` — once per cold start, alongside `app.launched`.
 * `previous_session_id` joins to everything the dead run emitted, so the
 * dashboard states a rate and the case work follows the id back to the route
 * it died on.
 */
internal fun logPreviousRun(previousRun: PreviousRun) {
    KLog.logEvent(
        "app.previous_run",
        "outcome" to previousRun.outcome.value,
        "previous_session_id" to previousRun.sessionId,
        "previous_run_age_sec" to previousRun.ageSeconds,
    )
}
