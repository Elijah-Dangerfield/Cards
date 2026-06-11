package com.dangerfield.cards.libraries.storage.impl

import com.dangerfield.cards.libraries.cards.UserScopedClearer
import com.dangerfield.cards.libraries.cards.storage.db.ClearableDao
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * When the active user changes (account switch or sign-out / delete), wipe
 * every user-scoped Room table for the departing user.
 *
 * Each `@Dao` in the user-data database extends [ClearableDao] and is
 * multibound into the set this cleaner consumes — adding a new DAO is a
 * compile-time wire-up, not a list edit. One DAO failing logs and continues;
 * one bad row can't block the rest.
 *
 * Runs as part of the [UserScopedClearer] dump the auth layer awaits *before*
 * announcing the new user, so the incoming user's data can't be written into a
 * table we're about to clear.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = UserScopedClearer::class)
@Inject
class UserScopedDaoCleaner(
    private val clearableDaos: Set<ClearableDao>,
) : UserScopedClearer {
    private val logger = KLog.withTag("UserScopedReset")

    override suspend fun clear(previousUserId: String) {
        clearableDaos.forEach { dao ->
            Catching { dao.deleteAll() }
                .onFailure { logger.w(it) { "deleteAll failed for ${dao::class.simpleName ?: dao::class}" } }
        }
    }
}
