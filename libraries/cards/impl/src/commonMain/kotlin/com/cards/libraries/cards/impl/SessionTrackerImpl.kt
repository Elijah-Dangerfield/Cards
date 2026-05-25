package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.cards.Session
import com.dangerfield.cards.libraries.cards.SessionStartReason
import com.dangerfield.cards.libraries.cards.SessionTracker
import com.dangerfield.cards.libraries.core.logging.KLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock

/**
 * Session boundaries from the lifecycle bus.
 *
 * On registration we already know cold-boot is imminent
 * ([AppEventDispatcher] fires [AppEvent.ColdBoot] + the first
 * [AppEvent.OnForeground] back-to-back); session #1 is published the
 * moment we see the [AppEvent.ColdBoot]. Subsequent foregrounds check
 * how long the app was in the background — anything at or above
 * [SessionTracker.BACKGROUND_ROLLOVER_MS] rolls to a fresh session id.
 *
 * **Why background gap, not foreground tick?** We want
 * "user came back after a meaningful gap" semantics. A brief detour
 * into the system share sheet shouldn't invalidate cached data; an
 * overnight return should.
 *
 * **State is in-memory, not persisted.** Each cold start resets the
 * counter to 1. Per-launch identifier is enough for the only consumer
 * shape we care about — repos asking "have I fetched this session?"
 * The persisted `AppData.lastSessionEndedAt` covers the "first ever
 * launch" question separately.
 *
 * **Thread safety:** all lifecycle callbacks fire on the platform main
 * thread (Android `Lifecycle.Event`, iOS `UIApplication` notifications)
 * — no need to serialize the volatile `lastBackgroundedAtMs` write
 * against the foreground read. The state flow's atomic updates handle
 * any downstream re-fanning.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@ContributesBinding(AppScope::class, boundType = SessionTracker::class)
@Inject
class SessionTrackerImpl(
    private val clock: Clock,
) : SessionTracker, AppEventListener {

    private val logger = KLog.withTag("SessionTracker")

    /**
     * Sentinel session that exists before cold-boot lands. No real
     * consumer should ever read this — [SessionTracker] is built so
     * `current` is always the result of a real cold-boot or rollover
     * by the time anyone subscribes (DI graph init happens before
     * Compose first frame, and [AppEventDispatcher] fires cold-boot
     * on first foreground which is also before any UI). Kept as a
     * harmless default so [current] is non-null in the unlikely
     * pre-init window rather than throwing.
     */
    private val state = MutableStateFlow(
        Session(id = 0L, startedAtMs = 0L, reason = SessionStartReason.ColdBoot),
    )
    private var lastBackgroundedAtMs: Long? = null
    private var nextSessionId: Long = 1L

    override val current: Session get() = state.value

    override fun observe(): Flow<Session> = state.asStateFlow()

    override fun onColdBoot(event: AppEvent.ColdBoot) {
        startNewSession(SessionStartReason.ColdBoot)
    }

    override fun onForeground(event: AppEvent.OnForeground) {
        if (event.isColdBoot) {
            // Cold boot's session was already published by [onColdBoot]
            // immediately above — [AppEventDispatcher] dispatches them
            // back-to-back. Skip so we don't double-count session #1.
            return
        }
        val backgroundedAtMs = lastBackgroundedAtMs ?: return
        val gap = clock.now().toEpochMilliseconds() - backgroundedAtMs
        if (gap >= SessionTracker.BACKGROUND_ROLLOVER_MS) {
            startNewSession(SessionStartReason.BackgroundRollover(backgroundedForMs = gap))
        }
    }

    override fun onBackground(event: AppEvent.OnBackground) {
        lastBackgroundedAtMs = clock.now().toEpochMilliseconds()
    }

    private fun startNewSession(reason: SessionStartReason) {
        val next = Session(
            id = nextSessionId++,
            startedAtMs = clock.now().toEpochMilliseconds(),
            reason = reason,
        )
        state.value = next
        logger.i { "Started session ${next.id} (reason=${reason::class.simpleName})" }
    }
}
