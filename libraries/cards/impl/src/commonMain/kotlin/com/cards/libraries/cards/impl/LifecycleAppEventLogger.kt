package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.logEvent
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Emits the `app.foregrounded` / `app.backgrounded` app events off the
 * lifecycle bus. Session length is derived downstream as the span between
 * them per session_id; `app.launched` (GrafanaAppEvents) covers the cold
 * start itself, so `cold_start` here just lets queries separate the boot
 * foreground from a warm return.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@Inject
class LifecycleAppEventLogger : AppEventListener {

    override fun onForeground(event: AppEvent.OnForeground) {
        KLog.logEvent("app.foregrounded", "cold_start" to event.isColdBoot)
    }

    override fun onBackground(event: AppEvent.OnBackground) {
        KLog.logEvent("app.backgrounded")
    }
}
