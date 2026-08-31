package com.dangerfield.cards.libraries.navigation.impl

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import me.tatarka.inject.annotations.Inject

@Inject
class SessionExpiredViewModel(
    private val authRepository: AuthRepository,
    private val appCache: AppCache,
) : SEAViewModel<
    SessionExpiredViewModel.State,
    SessionExpiredViewModel.Event,
    SessionExpiredViewModel.Action,
    >(initialStateArg = State()) {

    data class State(
        val retrying: Boolean = false,
        val retryFailed: Boolean = false,
    )

    sealed interface Event {
        data object SessionRestored : Event
        data object LoggedOut : Event
    }

    sealed interface Action {
        data object Retry : Action
        data object Logout : Action
    }

    override suspend fun handleAction(action: Action) {
        when (action) {
            Action.Retry -> action.retry()
            Action.Logout -> action.logout()
        }
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

    private suspend fun Action.logout() {
        authRepository.signOut()
        appCache.update { it.copy(hasUserOnboarded = false) }
        sendEvent(Event.LoggedOut)
    }
}
