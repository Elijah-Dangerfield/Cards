package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.logEvent
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Emits the `app.foregrounded` / `app.backgrounded` app events off the
 * lifecycle bus. `app.backgrounded` carries `session_duration_sec` — whole
 * seconds since the matching foreground, monotonic-clock based so a
 * wall-clock change mid-session can't corrupt it — which makes session
 * length a direct query instead of a per-session_id span join.
 * `app.launched` (GrafanaAppEvents) covers the cold start itself, so
 * `cold_start` here just lets queries separate the boot foreground from a
 * warm return.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
class LifecycleAppEventLogger(
    private val timeSource: TimeSource,
) : AppEventListener {

    @Inject
    constructor() : this(TimeSource.Monotonic)

    private var foregroundedAt: TimeMark? = null

    override fun onForeground(event: AppEvent.OnForeground) {
        foregroundedAt = timeSource.markNow()
        KLog.logEvent("app.foregrounded", "cold_start" to event.isColdBoot)
    }

    override fun onBackground(event: AppEvent.OnBackground) {
        val sessionDurationSec = foregroundedAt?.elapsedNow()?.inWholeSeconds
        foregroundedAt = null
        // logEvent drops null attribute values, so a background without a
        // matching foreground just omits the attribute.
        KLog.logEvent("app.backgrounded", "session_duration_sec" to sessionDurationSec)
    }
}
