package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.core.AutoInit
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.RunWhenRetry
import com.dangerfield.cards.libraries.flowroutines.runWhen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Single owner of the "when do user-scoped stores reconcile with the server"
 * policy. One level-based `runWhen` per [UserScopedSyncer]: sync whenever an
 * account is active (including already-active at subscribe — the lost-edge
 * boot race can't happen against a level), re-sync on warm foreground and on
 * connectivity returning, retry failures with backoff while the account holds.
 *
 * A store's own [UserScopedSyncer.localWrites] also refire its loop: at most
 * once per [LOCAL_WRITE_FLUSH_INTERVAL] with a trailing flush, plus once when
 * the app backgrounds with writes still unsent. Before this, hands queued
 * during a session waited for the next app open, and a player who never came
 * back never reached the server at all.
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
            val unsentWrites = MutableStateFlow(false)
            val flushWrites = merge(
                syncer.localWrites
                    .onEach { unsentWrites.value = true }
                    .throttleLatest(LOCAL_WRITE_FLUSH_INTERVAL),
                triggers.backgrounded,
            ).filter { unsentWrites.value }

            appScope.runWhen(
                key = triggers.activeAccount,
                refireOn = merge(triggers.warmForeground, triggers.cameOnline, flushWrites),
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
                    unsentWrites.value = false
                    registry.tracked(account.userId) {
                        syncer.sync()
                            .onFailure { unsentWrites.value = true }
                            .logOnFailure { "${syncer::class.simpleName} sync failed for ${account.userId}" }
                    }
                }
            }
        }
    }

    companion object {
        /**
         * One write-driven flush a minute per store keeps a long session well
         * inside the server's 480/hour/IP progression-write bucket, which the
         * stats, XP and play-style syncs share.
         */
        val LOCAL_WRITE_FLUSH_INTERVAL = 60.seconds
    }
}

/**
 * Emits the first value straight away, then at most the latest value per
 * [period], so a burst ends in a trailing emission instead of being dropped.
 */
private fun <T> Flow<T>.throttleLatest(period: Duration): Flow<T> = flow {
    conflate().collect { value ->
        emit(value)
        delay(period)
    }
}
