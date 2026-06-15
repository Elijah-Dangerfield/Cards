package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.cards.InAppMessageManager
import com.dangerfield.cards.libraries.cards.UserMessage
import com.dangerfield.cards.libraries.cards.UserMessageRepository
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implements the "one dialog per foreground" policy by reacting to
 * cold-boot + warm-foreground events. On each event:
 *
 *   1. Await auth (avoid 401ing the sync against an onboarding
 *      anonymous user mid-creation). We only need to know auth landed —
 *      not the profile details.
 *   2. Best-effort sync — pulls fresh server state, ships pending acks.
 *      Failure is non-fatal: we fall through to step 3 on the cached
 *      local DB.
 *   3. If [current] is already non-null (the previous dialog hasn't
 *      been dismissed yet), do nothing. Otherwise consume the next
 *      eligible dialog and surface it via [current].
 *
 * The `serializeForegroundsMutex` makes the read-then-write to
 * [current] race-free against rapid cold-boot + foreground events
 * firing back-to-back. Without it, two events overlapping could
 * consume two dialogs before the UI rendered the first one.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = InAppMessageManager::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@Inject
class InAppMessageManagerImpl(
    private val repository: UserMessageRepository,
    private val authRepositoryProvider: () -> AuthRepository,
    private val appScope: AppCoroutineScope,
) : InAppMessageManager, AppEventListener {

    private val logger = KLog.withTag("InAppMessages")
    private val authRepository: AuthRepository by lazy { authRepositoryProvider() }
    private val serializeForegroundsMutex = Mutex()
    private val _current = MutableStateFlow<UserMessage?>(null)

    override val current: StateFlow<UserMessage?> = _current.asStateFlow()

    override fun dismissCurrent() {
        _current.value = null
    }

    override fun onColdBoot(event: AppEvent.ColdBoot) {
        appScope.launch { onForegroundLikeEvent() }
    }

    override fun onForeground(event: AppEvent.OnForeground) {
        if (event.isColdBoot) return // cold-boot path owns this pass
        appScope.launch { onForegroundLikeEvent() }
    }

    override fun onUserChanged(event: AppEvent.UserChanged) {
        // Drop whatever's on screen on any user change (switch or sign-out) —
        // a message addressed to the departing user must not linger for the
        // next one. The message table itself is wiped by the user-scoped
        // clearer dump; this just clears the in-memory current.
        _current.value = null
        // On an account *switch* (a real prior user → a new one) re-sync and
        // surface the incoming user's messages now, instead of stranding them
        // until the next foreground. The cold-boot / sign-in case (previous ==
        // null) is already owned by onColdBoot, so this only covers switches —
        // avoiding a double sync at launch.
        if (event.current != null && event.previous != null) {
            appScope.launch { onForegroundLikeEvent() }
        }
    }

    private suspend fun onForegroundLikeEvent() = serializeForegroundsMutex.withLock {
        Catching {
            authRepository.current()
            // Best-effort sync — we still consume from cache on failure
            // so an offline user sees their previously-cached messages.
            repository.sync()

            if (_current.value != null) {
                logger.d { "Dialog still on screen; skipping consume for this foreground." }
                return@Catching
            }
            val next = repository.consumeNextDialog()
            if (next != null) {
                logger.d { "Surfacing dialog ${next.id} (\"${next.title}\")." }
                _current.value = next
            }
        }.onFailure {
            logger.w(it) { "Foreground dialog gate failed; leaving current state untouched." }
        }
    }
}
