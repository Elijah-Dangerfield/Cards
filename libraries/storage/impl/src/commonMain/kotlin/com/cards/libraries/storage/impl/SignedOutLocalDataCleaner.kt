package com.dangerfield.cards.libraries.storage.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.cards.storage.db.ClearableDao
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * On [AppEvent.SignedOut], wipe every user-scoped Room table.
 *
 * Each `@Dao` in the user-data database extends [ClearableDao] and is
 * multibound into the set this cleaner consumes — adding a new DAO is
 * a compile-time wire-up, not a list edit. One DAO failing logs and
 * continues; one bad row can't block the rest.
 *
 * Runs on [AppCoroutineScope] because [AppEventListener] callbacks
 * are non-suspend by contract.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@Inject
class SignedOutLocalDataCleaner(
    private val clearableDaos: Set<ClearableDao>,
    private val appScope: AppCoroutineScope,
) : AppEventListener {
    private val logger = KLog.withTag("SignOutCleanup")

    override fun onSignedOut(event: AppEvent.SignedOut) {
        appScope.launch {
            clearableDaos.forEach { dao ->
                Catching { dao.deleteAll() }
                    .onFailure { logger.w(it) { "deleteAll failed for ${dao::class.simpleName ?: dao::class}" } }
            }
        }
    }
}
