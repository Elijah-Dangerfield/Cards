package com.dangerfield.cards.features.onboarding.impl

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.IdentityRepository
import com.dangerfield.cards.libraries.identity.SignInOutcome
import me.tatarka.inject.annotations.Inject

/**
 * Drives the sign-in screen. On submit:
 *  - validate inputs locally (non-empty email + ≥ 6 char password)
 *  - call `identityRepository.signInWithEmail(...)`
 *  - on success: mark onboarded + emit [SignInEvent.NavigateToHome]
 *  - on failure: surface a specific error message
 *
 * No try/catch — the repository returns sealed outcome types so we just
 * pattern-match on the result.
 */
@Inject
class SignInViewModel(
    private val identityRepository: IdentityRepository,
    private val appCache: AppCache,
) : SEAViewModel<SignInState, SignInEvent, SignInAction>(
    initialStateArg = SignInState(),
) {

    override suspend fun handleAction(action: SignInAction) {
        when (action) {
            is SignInAction.EmailChanged -> action.updateState {
                it.copy(email = action.value, error = null)
            }
            is SignInAction.PasswordChanged -> action.updateState {
                it.copy(password = action.value, error = null)
            }
            is SignInAction.DismissError -> action.updateState { it.copy(error = null) }

            is SignInAction.Submit -> action.run {
                val current = state
                if (!current.canSubmit) return@run

                updateState { it.copy(isSubmitting = true, error = null) }

                val outcome = identityRepository.signInWithEmail(
                    email = current.email.trim(),
                    password = current.password,
                )

                when (outcome) {
                    is SignInOutcome.Success -> {
                        appCache.update { it.copy(hasUserOnboarded = true) }
                        updateState { it.copy(isSubmitting = false) }
                        sendEvent(SignInEvent.NavigateToHome)
                    }
                    is SignInOutcome.InvalidCredentials -> updateState {
                        it.copy(isSubmitting = false, error = "Email or password is incorrect.")
                    }
                    is SignInOutcome.EmailNotConfirmed -> {
                        updateState { it.copy(isSubmitting = false) }
                        sendEvent(SignInEvent.NavigateToVerifyEmail(outcome.email))
                    }
                    is SignInOutcome.NetworkError -> updateState {
                        it.copy(isSubmitting = false, error = "Couldn't reach the server. Check your connection.")
                    }
                    is SignInOutcome.Unknown -> updateState {
                        it.copy(isSubmitting = false, error = "Sign in failed. Please try again.")
                    }
                }
            }
        }
    }
}

data class SignInState(
    val email: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
) {
    /** Cheap client-side gate. Server is the canonical validator. */
    val canSubmit: Boolean
        get() = !isSubmitting && email.contains('@') && password.length >= MIN_PASSWORD_LENGTH

    companion object {
        const val MIN_PASSWORD_LENGTH = 6
    }
}

sealed interface SignInEvent {
    data object NavigateToHome : SignInEvent
    data class NavigateToVerifyEmail(val email: String) : SignInEvent
}

sealed interface SignInAction {
    data class EmailChanged(val value: String) : SignInAction
    data class PasswordChanged(val value: String) : SignInAction
    data object Submit : SignInAction
    data object DismissError : SignInAction
}
