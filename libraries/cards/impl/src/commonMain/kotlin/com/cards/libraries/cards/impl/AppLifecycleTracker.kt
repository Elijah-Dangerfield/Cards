package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock

/**
 * Tracks the bare minimum of app-lifecycle state we currently need —
 * an epoch-ms timestamp for the most recent session-end. Persisted in
 * [AppCache] so features that need a "first-ever session" signal
 * (welcome dialog, future "you've been gone a while" screens) can read
 * it without a dedicated session repo / DAO / entity.
 *
 * The previous `SessionRepository` + Room-backed `SessionEntity` stored
 * the same fact in a richer shape (UUID, start time, previous session
 * pointer) but no consumer ever read more than the count. We traded the
 * table for an [AppCache] field — one source of truth, no DAO, no
 * migration.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@Inject
class AppLifecycleTracker(
    private val appCache: AppCache,
    private val appScope: AppCoroutineScope,
    private val clock: Clock,
) : AppEventListener {

    private val logger = KLog.withTag("AppLifecycle")

    override fun onBackground(event: AppEvent.OnBackground) {
        // Use the app-scope (not the lifecycle scope) so the write isn't
        // cancelled when the app moves to background — the whole point of
        // this listener is to record that exact transition.
        appScope.launch {
            val now = clock.now().toEpochMilliseconds()
            appCache.update { it.copy(lastSessionEndedAt = now) }
            logger.d { "lastSessionEndedAt = $now" }
        }
    }

    override fun onForeground(event: AppEvent.OnForeground) {
        if (!event.isColdBoot) return
        // [AppData.pendingProfileHighlight] is an in-session "spotlight this
        // item on the Profile tab" signal the Shop sets right before hopping
        // to Profile. It's persisted only to survive tab restoreState within
        // the session — but persistence means a value left over from a prior
        // run would yank a cosmetic shelf to a non-first item on cold launch.
        // Clear it on cold boot so shelves always open at the start; the
        // in-session shop → profile spotlight still works (it's set after boot).
        appScope.launch {
            appCache.update { it.copy(pendingProfileHighlight = null) }
            logger.d { "Cleared stale pendingProfileHighlight on cold boot" }
        }
    }
}
