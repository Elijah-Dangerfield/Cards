package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEvents
import com.dangerfield.cards.libraries.core.AppState
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * The account whose user-scoped data is live on this device. Data-class
 * equality IS the refire contract for `runWhen` keys built on [activeAccount]:
 * a claim (same id, `isAnonymous` flips) is a key change and refires, while a
 * re-emission of the same session is not.
 */
data class ActiveAccount(val userId: String, val isAnonymous: Boolean)

/**
 * The app-state levels and edges that data-freshness work hangs off, derived
 * once so every consumer agrees on the semantics:
 *
 * - [activeAccount] — level; from [AuthRepository.observe], which replays the
 *   resolved state to late subscribers (no boot race) and emits only *after*
 *   the departing user's local data is cleared (a new user's sync can never
 *   race the old user's wipe).
 * - [warmForeground] — edge; app resumed with a session already live. Reads
 *   the replay-free stream so a stale pre-subscribe foreground can't
 *   double-fire at activation. Cold-boot foregrounds are excluded: boot syncs
 *   belong to [activeAccount] firing when auth resolves.
 * - [cameOnline] — edge; connectivity regained, derived from the
 *   [AppState.isOffline] level (not the replayed [AppEvent.ConnectivityRegained]
 *   edge, which could spuriously refire at boot from the bus's replay slot).
 *   Initial value dropped, flaps debounced.
 */
@SingleIn(AppScope::class)
@Inject
class SyncTriggers(
    authRepositoryProvider: () -> AuthRepository,
    appEventsProvider: () -> AppEvents,
    appState: AppState,
) {

    // Deferred: SyncTriggers is constructed by AppEventListeners inside the
    // event dispatcher's own construction, and auth depends on the dispatcher
    // too. Nothing here is touched until a trigger flow is collected, which
    // only happens from post-construction coroutines.
    private val authRepository by lazy { authRepositoryProvider() }
    private val appEvents by lazy { appEventsProvider() }

    val activeAccount: Flow<ActiveAccount?> =
        flow { emitAll(authRepository.observe()) }.map { state ->
            (state as? AuthState.Authenticated)?.let {
                ActiveAccount(userId = it.userId, isAnonymous = it.isAnonymous)
            }
        }

    val warmForeground: Flow<Unit> = flow {
        emitAll(
            appEvents.live()
                .filterIsInstance<AppEvent.OnForeground>()
                .filter { !it.isColdBoot }
                .map { },
        )
    }

    @OptIn(FlowPreview::class)
    val cameOnline: Flow<Unit> = appState.isOffline
        .drop(1)
        .debounce(ONLINE_DEBOUNCE_MS)
        .filter { offline -> !offline }
        .map { }

    private companion object {
        const val ONLINE_DEBOUNCE_MS: Long = 750L
    }
}
