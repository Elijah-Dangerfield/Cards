package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.UserScopedClearer
import com.dangerfield.cards.libraries.cards.UserScopedDataReset
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Runs every [UserScopedClearer] for a departing user, in series, swallowing
 * (and logging) per-clearer failures so one bad store can't block the rest —
 * or the auth transition that awaits this. Order between clearers doesn't
 * matter: each owns an independent store.
 *
 * The set is assembled across modules via Anvil multibindings (DAO tables in
 * `:libraries:storage:impl`, the profile mirror in `:libraries:identity:impl`,
 * account-scoped settings here), so this collector never needs to know what
 * the concrete stores are.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = UserScopedDataReset::class)
@Inject
class DefaultUserScopedDataReset(
    private val clearers: Set<UserScopedClearer>,
) : UserScopedDataReset {

    private val logger = KLog.withTag("UserScopedReset")

    override suspend fun clearFor(previousUserId: String) {
        logger.i { "Clearing ${clearers.size} user-scoped store(s) for departing user $previousUserId" }
        clearers.forEach { clearer ->
            Catching { clearer.clear(previousUserId) }
                .onFailure {
                    logger.w(it) { "clear failed for ${clearer::class.simpleName ?: clearer::class}" }
                }
        }
    }
}
