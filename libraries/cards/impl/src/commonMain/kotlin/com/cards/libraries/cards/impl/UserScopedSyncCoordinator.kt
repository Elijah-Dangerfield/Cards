package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEvents
import com.dangerfield.cards.libraries.core.AutoInit
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Single owner of the "re-sync user-scoped stores on the right identity/lifecycle
 * edges" rule that the user-scoped repos used to each duplicate. Collects the app
 * event stream once and fans the relevant edges out to every [UserScopedSyncer].
 *
 * Triggers:
 *  - [AppEvent.UserChanged] to a non-null user — sign-in, cold-boot resolve, or
 *    switch (the prior user's local data is already wiped by the user-scoped clear
 *    before this fires). A sign-out (current == null) just clears the active user.
 *  - [AppEvent.AccountClaimed] — same id, now claimed; UserChanged never fires.
 *  - A warm [AppEvent.OnForeground] while a user is active. Cold-boot foregrounds
 *    are owned by the UserChanged path, so they're skipped.
 *
 * [AutoInit] so the collector is attached at boot, before any of those edges land.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AutoInit::class, multibinding = true)
@Inject
class UserScopedSyncCoordinator(
    appEvents: AppEvents,
    private val syncers: Set<UserScopedSyncer>,
    private val appScope: AppCoroutineScope,
) : AutoInit {

    private val logger = KLog.withTag("UserScopedSync")

    init {
        appScope.launch {
            var activeUserId: String? = null
            appEvents.collect { event ->
                when (event) {
                    is AppEvent.UserChanged -> {
                        activeUserId = event.current
                        if (event.current != null) syncAll("userChanged")
                    }
                    is AppEvent.AccountClaimed -> {
                        activeUserId = event.userId
                        syncAll("accountClaimed")
                    }
                    is AppEvent.OnForeground ->
                        if (!event.isColdBoot && activeUserId != null) syncAll("foreground")
                    else -> Unit
                }
            }
        }
    }

    private fun syncAll(trigger: String) {
        syncers.forEach { syncer ->
            appScope.launch {
                Catching { syncer.sync() }
                    .logOnFailure { "${syncer::class.simpleName} sync failed on $trigger" }
            }
        }
    }
}
