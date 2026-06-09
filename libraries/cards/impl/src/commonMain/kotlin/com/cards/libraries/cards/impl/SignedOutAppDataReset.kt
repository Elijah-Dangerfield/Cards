package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.cards.resetAccountScoped
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
 * On [AppEvent.SignedOut], reset the account-scoped fields of [AppData] to
 * their defaults (see [resetAccountScoped]) while keeping device-scoped
 * settings. Completes the sign-out reset alongside `SignedOutLocalDataCleaner`
 * (DB tables) and `ProfileRepositoryImpl` (profile caches).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@Inject
class SignedOutAppDataReset(
    private val appCache: AppCache,
    private val appScope: AppCoroutineScope,
) : AppEventListener {
    private val logger = KLog.withTag("SignOutCleanup")

    override fun onSignedOut(event: AppEvent.SignedOut) {
        appScope.launch {
            Catching { appCache.update { it.resetAccountScoped() } }
                .logOnFailure { "Account-scoped AppData reset on sign-out failed" }
        }
    }
}
