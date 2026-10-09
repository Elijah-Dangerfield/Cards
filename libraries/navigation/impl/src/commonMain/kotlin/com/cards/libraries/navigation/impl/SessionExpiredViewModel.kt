package com.dangerfield.cards.libraries.navigation.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.core.AppState
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.logEvent
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.StrandedAccount
import com.dangerfield.cards.libraries.identity.auth.StrandedAccountStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject

/**
 * What the blocking session-expired screen can honestly offer right now.
 *
 * The distinction is the whole point of AUTH-34: a claimed account is one
 * sign-in away from coming back, and the old screen said nothing about it, so
 * the user walked into a brand-new guest account instead.
 */
sealed interface SessionRecovery {

    /**
     * We can't reach the server, so we can't even confirm the session is gone.
     * Retry is the only honest action: offering to sign in would send the user
     * to a form that can't submit, and offering to start over would trade a
     * real account away over a dropped connection. Flips to one of the others
     * on its own as soon as connectivity returns.
     */
    data object Offline : SessionRecovery

    /**
     * A claimed account this device still belongs to. Signing back in restores
     * it whole, so that's the primary action. [displayName] is null when no
     * durable record was written (a server-side rejection rather than the
     * local session loss the healer records), in which case we can say the
     * account is safe but not name it.
     */
    data class SignInToRestore(val displayName: String?) : SessionRecovery

    /**
     * An anonymous account has no credential to come back with, so there is
     * nothing to sign in to. Retry, or start a new guest session.
     */
    data object GuestOnly : SessionRecovery
}

/**
 * Drives the blocking screen the user lands on when their session is gone.
 *
 * Retry is the cheap path (a refreshable session just needed a nudge). What
 * this adds for AUTH-34 is the other half: reading the durable
 * [StrandedAccountStore] record so the screen can name the account that is
 * sitting on the server, and making the guest path a deliberate choice that
 * says what it costs instead of a peer option in generic onboarding.
 *
 * The record is read here rather than passed through [SessionExpiredRoute]
 * because a display name is user data that would end up in saved nav state,
 * and because the record can change under a screen that outlives it.
 */
@Inject
class SessionExpiredViewModel(
    private val authRepository: AuthRepository,
    private val appCache: AppCache,
    private val strandedAccounts: StrandedAccountStore,
    private val appState: AppState,
    @Assisted private val wasAnonymous: Boolean,
) : SEAViewModel<
    SessionExpiredViewModel.State,
    SessionExpiredViewModel.Event,
    SessionExpiredViewModel.Action,
    >(
    initialStateArg = State(
        recovery = recoveryFor(
            stranded = null,
            offline = appState.isOffline.value,
            wasAnonymous = wasAnonymous,
        ),
    ),
) {

    private val logger = KLog.withTag("SessionExpiredViewModel")
    private val stranded = MutableStateFlow<StrandedAccount?>(null)

    data class State(
        val recovery: SessionRecovery,
        val retrying: Boolean = false,
        val retryFailed: Boolean = false,
    )

    sealed interface Event {
        data object SessionRestored : Event
        data object Abandoned : Event
    }

    sealed interface Action {
        data object Load : Action
        data class RecoveryChanged(val recovery: SessionRecovery) : Action
        data object Retry : Action
        data object Abandon : Action
    }

    init {
        viewModelScope.launch {
            combine(stranded, appState.isOffline) { account, offline ->
                recoveryFor(account, offline, wasAnonymous)
            }
                .distinctUntilChanged()
                .collect { recovery -> takeAction(Action.RecoveryChanged(recovery)) }
        }
        takeAction(Action.Load)
    }

    override suspend fun handleAction(action: Action) {
        when (action) {
            Action.Load -> action.load()
            is Action.RecoveryChanged -> action.updateState { it.copy(recovery = action.recovery) }
            Action.Retry -> action.retry()
            Action.Abandon -> action.abandon()
        }
    }

    private suspend fun Action.load() {
        val record = Catching { strandedAccounts.read() }
            .logOnFailure { "Reading the stranded-account record failed; recovery can't name the account" }
            .getOrNull()
        stranded.value = record

        // The denominator for the abandon rate below: without it we'd know how
        // often people walk away from a recoverable account but not out of how
        // many chances.
        logger.logEvent(
            "auth.recovery_offered",
            "was_anonymous" to (record?.isAnonymous ?: wasAnonymous),
            "named" to (record?.displayName?.isNotBlank() == true),
            "offline" to appState.isOffline.value,
        )
    }

    private suspend fun Action.retry() {
        updateState { it.copy(retrying = true, retryFailed = false) }
        val outcome = authRepository.retry()
        if (outcome is AuthState.Authenticated) {
            appCache.update { it.copy(hasUserOnboarded = true, onboardingAttempt = null) }
            sendEvent(Event.SessionRestored)
        } else {
            updateState { it.copy(retrying = false, retryFailed = true) }
        }
    }

    private suspend fun Action.abandon() {
        val record = stranded.value
        logger.logEvent(
            "auth.stranded_account_abandoned",
            "was_anonymous" to (record?.isAnonymous ?: wasAnonymous),
            "stranded_user_id" to record?.userId,
        )

        authRepository.signOut()

        // The healer refuses to mint while a record exists, so leaving it
        // behind would leave the device unable to make the guest account the
        // user just asked for. Their choice is what lifts the refusal.
        Catching { strandedAccounts.clear() }
            .logOnFailure { "Clearing the stranded-account record failed; the guest path may stay refused" }

        appCache.update { it.copy(hasUserOnboarded = false) }
        sendEvent(Event.Abandoned)
    }
}

private fun recoveryFor(
    stranded: StrandedAccount?,
    offline: Boolean,
    wasAnonymous: Boolean,
): SessionRecovery = when {
    offline -> SessionRecovery.Offline
    // The record is the better witness when we have one: the route arg is
    // whatever the teardown believed, the record is what the healer saw.
    stranded?.isAnonymous ?: wasAnonymous -> SessionRecovery.GuestOnly
    else -> SessionRecovery.SignInToRestore(stranded?.displayName?.takeIf { it.isNotBlank() })
}
