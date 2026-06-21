package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.core.AppState
import com.dangerfield.cards.libraries.core.AutoInit
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.identity.auth.AccountCreationState
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.GuestAccountCreator
import com.dangerfield.cards.libraries.identity.auth.PendingIdentity
import com.dangerfield.cards.libraries.identity.auth.SignInOutcome
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * App-scoped [GuestAccountCreator]. Runs the create job on [AppCoroutineScope]
 * so it isn't tied to the onboarding ViewModel's lifecycle — the user can page
 * through the rest of onboarding (or the VM can be recreated) while creation is
 * in flight.
 *
 * Success is defined by an anonymous session existing; the profile apply is
 * best-effort (the server seeds a usable generated name on first contact, so a
 * failed patch just leaves that default in place — not a creation failure).
 *
 * **Durable + self-healing.** [start] persists the chosen identity via
 * [PendingGuestAccountStore] *before* attempting, and only clears it on success.
 * As an [AutoInit] it re-reads that store at app launch: if a guest account is
 * still owed (creation failed offline last session), it re-attempts immediately
 * — so a user who onboarded offline gets their real guest account the next time
 * the app opens online, with the same name/avatar, instead of being stranded as
 * a local-only Fallback forever.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = GuestAccountCreator::class)
@ContributesBinding(AppScope::class, boundType = AutoInit::class, multibinding = true)
@Inject
class DefaultGuestAccountCreator(
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
    private val pendingStore: PendingGuestAccountStore,
    private val appState: AppState,
    private val appScope: AppCoroutineScope,
) : GuestAccountCreator, AutoInit {

    private val logger = KLog.withTag("GuestAccountCreator")
    private val _state = MutableStateFlow<AccountCreationState>(AccountCreationState.Idle)
    override val state: StateFlow<AccountCreationState> = _state.asStateFlow()

    init {
        // Resume an owed creation from a previous session (e.g. onboarded
        // offline). No-op when nothing's pending.
        appScope.launch {
            val owed = Catching { pendingStore.read() }.getOrNull() ?: return@launch
            // Guard against double-creation: if a session already exists (last
            // session's createGuestSession succeeded but the clear() didn't
            // persist — e.g. a crash in between), reconcile instead of minting
            // a second anonymous account.
            if (authRepository.current() is AuthState.Authenticated) {
                logger.d { "Owed guest account but already authenticated — clearing without re-creating" }
                Catching { pendingStore.clear() }.logOnFailure { "Clearing reconciled pending account failed" }
                _state.value = AccountCreationState.Succeeded
                return@launch
            }
            logger.i { "Resuming owed guest-account creation from a prior session" }
            start(owed)
        }

        // Heal mid-session: when connectivity returns and a creation is still
        // Failed, retry it — so an offline guest doesn't have to relaunch.
        // (isOffline is a StateFlow, so it already only emits on change.)
        appScope.launch {
            appState.isOffline.collect { offline ->
                if (!offline && _state.value is AccountCreationState.Failed) {
                    logger.i { "Back online — retrying failed guest-account creation" }
                    retry()
                }
            }
        }
    }

    override fun start(identity: PendingIdentity) {
        // Don't restart a run that's in flight or already done; a Failed state
        // is restartable (that's the retry path).
        val current = _state.value
        if (current is AccountCreationState.InProgress || current is AccountCreationState.Succeeded) {
            logger.d { "start: ignored — already ${current::class.simpleName}" }
            return
        }
        logger.d { "start: launching guest-account creation" }
        _state.value = AccountCreationState.InProgress
        // Persist the owed creation *before* the attempt, on app scope, the
        // instant the user commits. The onboarding VM can mark onboarded +
        // navigate Home moments later — tearing down its own scope; routing the
        // durable write through the (un-cancellable) app scope here, ahead of
        // createGuestSession, makes the record un-loseable. Even a process kill
        // mid-attempt then leaves us able to resume next launch with the same
        // name/avatar instead of stranding the user as a permanent Fallback.
        // set → create → clear stays sequential in one coroutine so a fast
        // success can't be re-overwritten by a late persist.
        appScope.launch {
            Catching { pendingStore.set(identity) }
                .logOnFailure { "Persisting pending guest account failed" }
            runCreate(identity)
        }
    }

    override fun retry() {
        val failed = _state.value as? AccountCreationState.Failed ?: return
        logger.d { "retry: re-attempting failed guest-account creation" }
        start(failed.identity)
    }

    override suspend fun awaitTerminal(): AccountCreationState =
        state.first { it is AccountCreationState.Succeeded || it is AccountCreationState.Failed }

    private suspend fun runCreate(identity: PendingIdentity) {
        // The owed creation was already persisted by the caller ([start] /
        // [retry] / init-resume) before we got here, so a kill mid-attempt can
        // still resume next launch.
        val next = when (val outcome = authRepository.createGuestSession()) {
            is SignInOutcome.Success -> {
                // Session is live → account exists. Apply the chosen identity
                // best-effort; failure here is non-fatal (server's generated
                // default stands and the user can rename later).
                Catching {
                    profileRepository.update(
                        displayName = identity.displayName,
                        avatarEmoji = identity.avatarEmoji,
                        avatarBackgroundColor = identity.avatarBackgroundColor,
                    )
                }.logOnFailure { "Guest profile apply failed (non-fatal)" }
                Catching { pendingStore.clear() }.logOnFailure { "Clearing pending guest account failed" }
                logger.i { "Guest account created" }
                AccountCreationState.Succeeded
            }
            else -> {
                logger.w { "Guest account creation failed: ${outcome::class.simpleName}" }
                AccountCreationState.Failed(identity, cause = outcome.causeOrNull())
            }
        }
        _state.value = next
    }

    private fun SignInOutcome.causeOrNull(): Throwable? = when (this) {
        is SignInOutcome.NetworkError -> cause
        is SignInOutcome.Unknown -> cause
        else -> null
    }
}
