package com.dangerfield.cards.libraries.storage.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.storage.impl.db.AppDatabaseProvider
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * On [AppEvent.SignedOut], wipe every Room table in the user-data
 * [AppDatabase]. Single listener instead of one-per-repository because
 * every entity in this database is per-user — chips, inventory,
 * equipment, progression, achievements, the user row itself, the
 * play-session log. Any future shared / app-scoped cache (product
 * catalog snapshot, avatar pack response, etc.) belongs in a separate
 * Room database so it survives sign-out without needing per-table
 * carve-outs here.
 *
 * Runs on [AppCoroutineScope] because [AppEventListener] callbacks are
 * non-suspend by contract. Failures are logged but don't crash the
 * sign-out flow — the next session still starts cleanly because the
 * Supabase JWT changes; at worst a few stale rows linger until the
 * next sign-out attempt.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@Inject
class SignedOutLocalDataCleaner(
    private val databaseProvider: AppDatabaseProvider,
    private val appScope: AppCoroutineScope,
) : AppEventListener {
    override fun onSignedOut(event: AppEvent.SignedOut) {
        appScope.launch {
            Catching { databaseProvider.database.clearAllTables() }
                .onFailure { KLog.withTag("SignOutCleanup").w(it) { "clearAllTables failed" } }
        }
    }
}
