package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.UserScopedClearer
import com.dangerfield.cards.libraries.cards.UserScopedDataReset
import com.dangerfield.cards.libraries.cards.UserScopedWorkStopper
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.core.logging.KLog
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Runs every [UserScopedWorkStopper], then every [UserScopedClearer], for a
 * departing user — in that order, each awaited: an in-flight sync for the old
 * user must have finished cancelling before its stores are wiped, or its
 * writes can land mid-clear. Per-participant failures are swallowed (and
 * logged) so one bad store can't block the rest — or the auth transition that
 * awaits this. Order *between* clearers doesn't matter: each owns an
 * independent store.
 *
 * Both sets are assembled across modules via Anvil multibindings (DAO tables
 * in `:libraries:storage:impl`, the profile mirror in
 * `:libraries:identity:impl`, account-scoped settings and the sync-work
 * registry here), so this collector never needs to know what the concrete
 * stores are.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = UserScopedDataReset::class)
@Inject
class DefaultUserScopedDataReset(
    private val clearers: Set<UserScopedClearer>,
    private val stoppers: Set<UserScopedWorkStopper>,
    private val appCache: AppCache,
) : UserScopedDataReset {

    private val logger = KLog.withTag("UserScopedReset")

    override suspend fun ensureOwnedBy(userId: String) {
        val previousOwner = Catching { appCache.get().lastActiveUserId }
            .logOnFailure { "Reading the local data owner failed; skipping the ownership check" }
            .getOrNull()

        if (previousOwner == userId) return

        if (previousOwner != null) {
            logger.w {
                "Device-local data belongs to $previousOwner but $userId is signing in — " +
                    "dumping the previous owner's data before handing the device over"
            }
            clearFor(previousOwner)
        }

        Catching { appCache.update { it.copy(lastActiveUserId = userId) } }
            .logOnFailure { "Recording $userId as the local data owner failed" }
    }

    override suspend fun clearFor(previousUserId: String) {
        logger.i { "Stopping ${stoppers.size} work source(s), then clearing ${clearers.size} store(s) for departing user $previousUserId" }
        stoppers.forEach { stopper ->
            Catching { stopper.stopWorkFor(previousUserId) }
                .onFailure {
                    logger.w(it) { "stop failed for ${stopper::class.simpleName ?: stopper::class}" }
                }
        }
        clearers.forEach { clearer ->
            Catching { clearer.clear(previousUserId) }
                .onFailure {
                    logger.w(it) { "clear failed for ${clearer::class.simpleName ?: clearer::class}" }
                }
        }
        // Their data is gone, so nobody owns the device now. Releasing the record
        // here is what keeps [ensureOwnedBy] from dumping the same user a second
        // time when the next account signs in.
        Catching { appCache.update { it.copy(lastActiveUserId = null) } }
            .logOnFailure { "Releasing the local data owner after clearing $previousUserId failed" }
    }
}
