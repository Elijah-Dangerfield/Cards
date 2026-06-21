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
        // A user just became active — switch OR a session arriving on a cold
        // boot (previous == null), which is exactly the identity-heal path.
        // Re-sync + surface the incoming user's messages now. Gated on
        // Authenticated below, so a sign-out (current == null) no-ops.
        if (event.current != null) {
            appScope.launch { onForegroundLikeEvent() }
        }
    }

    override fun onAccountClaimed(event: AppEvent.AccountClaimed) {
        // A guest just claimed — flush + surface their messages now instead of
        // waiting for the next foreground (UserChanged never fired).
        appScope.launch { onForegroundLikeEvent() }
    }

    override fun onConnectivityRegained(event: AppEvent.ConnectivityRegained) {
        // Reconnect after offline — pull anything that landed while away.
        appScope.launch { onForegroundLikeEvent() }
    }

    private suspend fun onForegroundLikeEvent() = serializeForegroundsMutex.withLock {
        Catching {
            // Only sync when we actually hold a session — a tokenless sync 401s.
            // A session-less resolve skips the network and falls through to the
            // cached local DB below, so an unauthenticated/offline user still
            // sees their previously-cached messages. Re-fires when auth arrives
            // (UserChanged / AccountClaimed).
            authRepository.ifAuthenticated {
                // Best-effort sync — we still consume from cache on failure.
                repository.sync()
            } ?: logger.d { "Skipping message sync — not authenticated" }

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
