package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.cards.SessionTracker
import com.dangerfield.cards.libraries.cards.Telemetry
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.networking.InstallIdProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Mirrors the current app [com.dangerfield.cards.libraries.cards.Session]
 * onto the crash-reporting scope so every Sentry event — crashes and the
 * user-feedback the testers file — carries a `session_id` tag. The backend
 * stamps the same id (sent via `X-Session-Id`) onto its OTel traces and
 * logs, so one value pulls a session's frontend and backend telemetry.
 *
 * Started on cold boot, after [Telemetry.initialize] has run (init happens
 * in the platform entry point before any lifecycle event), so the tag is in
 * place before the first crash window. [SessionTracker.observe] replays the
 * current session on subscribe and re-emits on every rollover, so the tag
 * tracks the live session without further wiring.
 *
 * The install id rides along as a second `install_id` tag — stable across
 * sessions, handy for "every session from this tester." It hydrates async,
 * so we set it opportunistically whenever it's available on a session emit.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@Inject
class SessionTelemetryBinder(
    private val sessionTracker: SessionTracker,
    private val installIdProvider: InstallIdProvider,
    private val telemetry: Telemetry,
    private val appScope: AppCoroutineScope,
) : AppEventListener {

    private val logger = KLog.withTag("SessionTelemetry")
    private var job: Job? = null

    override fun onColdBoot(event: AppEvent.ColdBoot) {
        if (job != null) return
        job = appScope.launch {
            sessionTracker.observe()
                .distinctUntilChangedBy { it.uuid }
                .collect { session ->
                    telemetry.setSession(session.uuid)
                    installIdProvider.current()?.let { telemetry.setInstallId(it) }
                    logger.d { "Telemetry session set to ${session.uuid} (#${session.id})" }
                }
        }
    }
}
