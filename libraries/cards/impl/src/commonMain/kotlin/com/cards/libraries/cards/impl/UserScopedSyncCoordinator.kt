package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.core.AutoInit
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.core.logging.KLog
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
 * Each cycle runs [UserScopedWorkRegistry.tracked] so a user switch can also
 * cancel it *synchronously with the data clear* — the key-change cancellation
 * above only lands once the new auth emission reaches these loops, which is
 * after the departing user's stores were already wiped.
 *
 * [AutoInit] so the loops attach at boot before the user can navigate.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AutoInit::class, multibinding = true)
@Inject
class UserScopedSyncCoordinator(
    triggers: SyncTriggers,
    syncers: Set<UserScopedSyncer>,
    registry: UserScopedWorkRegistry,
    appScope: AppCoroutineScope,
) : AutoInit {

    private val logger = KLog.withTag("UserScopedSync")

    init {
        syncers.forEach { syncer ->
            appScope.runWhen(
                key = triggers.activeAccount,
                refireOn = merge(triggers.warmForeground, triggers.cameOnline),
                retry = RunWhenRetry.exponential(),
            ) { account ->
                if (triggers.isOffline.value) {
                    // An offline device can't reconcile — every attempt would
                    // burn the whole retry ladder failing the same way (ENG-34:
                    // one phone in a dead spot logged 59 error events). Park as
                    // success; the cameOnline refire re-runs the moment a route
                    // exists again.
                    logger.i { "${syncer::class.simpleName} sync deferred: device offline" }
                    Result.success(Unit)
                } else {
                    registry.tracked(account.userId) {
                        syncer.sync()
                            .logOnFailure { "${syncer::class.simpleName} sync failed for ${account.userId}" }
                    }
                }
            }
        }
    }
}
