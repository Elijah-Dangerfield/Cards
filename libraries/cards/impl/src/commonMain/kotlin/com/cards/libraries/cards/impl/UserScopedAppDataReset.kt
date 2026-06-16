package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.UserScopedClearer
import com.dangerfield.cards.libraries.cards.resetAccountScoped
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * When the active user changes, reset the account-scoped fields of [AppData] to
 * their defaults (see [resetAccountScoped]) while keeping device-scoped
 * settings. Completes the user-scoped dump alongside [UserScopedDaoCleaner]
 * (DB tables) and `UserScopedProfileCacheCleaner` (profile caches).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = UserScopedClearer::class)
@Inject
class UserScopedAppDataReset(
    private val appCache: AppCache,
) : UserScopedClearer {

    override suspend fun clear(previousUserId: String) {
        Catching { appCache.update { it.resetAccountScoped() } }
            .logOnFailure { "Account-scoped AppData reset on user change failed" }
    }
}
