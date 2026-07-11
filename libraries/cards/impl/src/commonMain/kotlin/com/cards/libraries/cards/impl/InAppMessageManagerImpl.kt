package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.cards.InAppMessageManager
import com.dangerfield.cards.libraries.cards.UserMessage
import com.dangerfield.cards.libraries.cards.UserMessageRepository
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.runWhen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implements the "one dialog per foreground" policy as a level-based loop:
 * a pass runs whenever an account becomes active (boot resolve, sign-in,
 * switch, claim) and re-runs on warm foreground and connectivity returning.
 * Each pass:
 *
 *   1. Best-effort sync — pulls fresh server state, ships pending acks.
 *      Failure is non-fatal: we fall through to step 2 on the cached
 *      local DB.
 *   2. If [current] is already non-null (the previous dialog hasn't
 *      been dismissed yet), do nothing. Otherwise consume the next
 *      eligible dialog and surface it via [current].
 *
 * `runWhen`'s serial loop makes passes single-flight: rapid boot +
 * foreground triggers coalesce instead of consuming two dialogs before the
 * UI rendered the first. The auth gate is the trigger key itself — a pass
 * can't fire tokenless, and a sign-out cancels an in-flight pass.
 *
 * Dropping the on-screen dialog when the user changes stays an
 * [AppEventListener] side effect — it's a synchronous moment reaction, not
 * reconcile work.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = InAppMessageManager::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@Inject
class InAppMessageManagerImpl(
    private val repository: UserMessageRepository,
    triggers: SyncTriggers,
    registry: UserScopedWorkRegistry,
    appScope: AppCoroutineScope,
) : InAppMessageManager, AppEventListener {

    private val logger = KLog.withTag("InAppMessages")
    private val _current = MutableStateFlow<UserMessage?>(null)

    override val current: StateFlow<UserMessage?> = _current.asStateFlow()

    init {
        appScope.runWhen(
            key = triggers.activeAccount,
            refireOn = merge(triggers.warmForeground, triggers.cameOnline),
        ) { account ->
            registry.tracked(account.userId) { surfaceNextDialog() }
        }
    }

    override fun dismissCurrent() {
        _current.value = null
    }

    override fun onUserChanged(event: AppEvent.UserChanged) {
        // A message addressed to the departing user must not linger for the
        // next one. The message table itself is wiped by the user-scoped
        // clearer dump; this just clears the in-memory current.
        _current.value = null
    }

    private suspend fun surfaceNextDialog(): Result<Unit> = Catching {
        repository.sync()
            .logOnFailure { "Message sync failed; consuming from the local cache." }

        if (_current.value != null) {
            logger.d { "Dialog still on screen; skipping consume for this pass." }
            return@Catching
        }
        val next = repository.consumeNextDialog()
        if (next != null) {
            logger.d { "Surfacing dialog ${next.id} (\"${next.title}\")." }
            _current.value = next
        }
    }.onFailure {
        logger.w(it) { "Dialog pass failed; leaving current state untouched." }
    }
}
