package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.core.AutoInit
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.RunWhenRetry
import com.dangerfield.cards.libraries.flowroutines.runWhen
import kotlinx.coroutines.flow.merge
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Single owner of the "when do user-scoped stores reconcile with the server"
 * policy. One level-based `runWhen` per [UserScopedSyncer]: sync whenever an
 * account is active (including already-active at subscribe — the lost-edge
 * boot race can't happen against a level), re-sync on warm foreground and on
 * connectivity returning, retry failures with backoff while the account holds.
 *
 * Per-syncer loops are independent: a failing wallet sync retries alone
 * without re-running the other stores, and each loop is single-flight with
 * trailing coalesce. Sign-out cancels in-flight syncs and pending backoff; a
 * user switch or a guest claiming their account (same id, `isAnonymous` flips)
 * is a key change that cancels and fires fresh.
 *
 * [AutoInit] so the loops attach at boot before the user can navigate.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AutoInit::class, multibinding = true)
@Inject
class UserScopedSyncCoordinator(
    triggers: SyncTriggers,
    syncers: Set<UserScopedSyncer>,
    appScope: AppCoroutineScope,
) : AutoInit {

    init {
        syncers.forEach { syncer ->
            appScope.runWhen(
                key = triggers.activeAccount,
                refireOn = merge(triggers.warmForeground, triggers.cameOnline),
                retry = RunWhenRetry.exponential(),
            ) { account ->
                syncer.sync()
                    .logOnFailure { "${syncer::class.simpleName} sync failed for ${account.userId}" }
            }
        }
    }
}
