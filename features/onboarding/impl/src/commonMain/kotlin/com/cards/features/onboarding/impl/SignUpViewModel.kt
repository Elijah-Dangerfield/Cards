package com.dangerfield.cards.features.onboarding.impl

import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.IdentityRepository
import com.dangerfield.cards.libraries.identity.SignUpOutcome
import me.tatarka.inject.annotations.Inject

/**
 * Drives sign-up. On submit:
 *  - validate locally (basic email shape, password ≥ 6 chars)
 *  - call `identityRepository.signUpWithEmail(...)`
 *  - on success: emit [SignUpEvent.NavigateToVerifyEmail] (we do NOT
 *    set `hasUserOnboarded = true` yet — that happens after the user
 *    actually confirms their email).
 *  - on failure: surface a specific error message
 */
@Inject
class SignUpViewModel(
    private val identityRepository: IdentityRepository,
) : SEAViewModel<SignUpState, SignUpEvent, SignUpAction>(
    initialStateArg = SignUpState(),
) {

    override suspend fun handleAction(action: SignUpAction) {
        when (action) {
            is SignUpAction.EmailChanged -> action.updateState {
                it.copy(email = action.value, error = null)
            }
            is SignUpAction.PasswordChanged -> action.updateState {
                it.copy(password = action.value, error = null)
            }
            is SignUpAction.DismissError -> action.updateState { it.copy(error = null) }

            is SignUpAction.Submit -> action.run {
                val current = state
                if (!current.canSubmit) return@run

                updateState { it.copy(isSubmitting = true, error = null) }

                val outcome = identityRepository.signUpWithEmail(
                    email = current.email.trim(),
                    password = current.password,
                )

                when (outcome) {
                    is SignUpOutcome.VerificationRequired -> {
                        updateState { it.copy(isSubmitting = false) }
                        sendEvent(SignUpEvent.NavigateToVerifyEmail(outcome.email))
                    }
                    is SignUpOutcome.EmailAlreadyRegistered -> updateState {
                        it.copy(isSubmitting = false, error = "That email is already in use. Try signing in instead.")
                    }
                    is SignUpOutcome.WeakPassword -> updateState {
                        it.copy(isSubmitting = false, error = "Pick a stronger password (at least ${SignUpState.MIN_PASSWORD_LENGTH} characters).")
                    }
                    is SignUpOutcome.InvalidEmail -> updateState {
                        it.copy(isSubmitting = false, error = "That email doesn't look right.")
                    }
                    is SignUpOutcome.NetworkError -> updateState {
                        it.copy(isSubmitting = false, error = "Couldn't reach the server. Check your connection.")
                    }
                    is SignUpOutcome.Unknown -> updateState {
                        it.copy(isSubmitting = false, error = "Sign up failed. Please try again.")
                    }
                }
            }
        }
    }
}

data class SignUpState(
    val email: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean
        get() = !isSubmitting && email.contains('@') && password.length >= MIN_PASSWORD_LENGTH

    companion object {
        const val MIN_PASSWORD_LENGTH = 6
    }
}

sealed interface SignUpEvent {
    data class NavigateToVerifyEmail(val email: String) : SignUpEvent
}

sealed interface SignUpAction {
    data class EmailChanged(val value: String) : SignUpAction
    data class PasswordChanged(val value: String) : SignUpAction
    data object Submit : SignUpAction
    data object DismissError : SignUpAction
}
