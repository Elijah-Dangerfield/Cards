package com.dangerfield.cards.libraries.telemetry.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Flushes the OTel pipe when the app backgrounds. The batch stage holds up
 * to a flush-tick's worth of records in RAM, and a backgrounded app can be
 * suspended (iOS) or killed (both platforms) before the next tick — without
 * this, the tail of every session (`app.backgrounded` itself included) rides
 * only in memory. The flush pushes the batch into the disk buffer and
 * attempts an export in the runtime the OS still grants after backgrounding.
 *
 * Holds the tree through a settable reference instead of injecting
 * [GrafanaAppEvents]: an [AppEventListener] whose dependencies reach the
 * config system closes a DI cycle (config → dispatcher → listener set), the
 * same cycle that split [AppLaunchedEmitter] out. Stays null in builds
 * without Grafana credentials.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AppEventListener::class, multibinding = true)
@Inject
class TelemetryBackgroundFlusher(
    private val appScope: AppCoroutineScope,
) : AppEventListener {

    var tree: GrafanaLogTree? = null

    override fun onBackground(event: AppEvent.OnBackground) {
        val target = tree ?: return
        appScope.launch { target.flushExports() }
    }
}
