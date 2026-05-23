package com.dangerfield.cards.features.onboarding.impl

import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.LinkEmailIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.SignUpOutcome
import me.tatarka.inject.annotations.Inject

/**
 * Drives sign-up. On submit:
 *  - validate locally (basic email shape, password ≥ 6 chars)
 *  - if the current session is anonymous (the typical guest-claim path),
 *    call `authRepository.linkEmailIdentity(...)` so chips / XP /
 *    history stay on the same userId; otherwise fall back to
 *    `authRepository.signUpWithEmail(...)`.
 *  - on success: emit [SignUpEvent.NavigateToVerifyEmail] (we do NOT
 *    set `hasUserOnboarded = true` yet — that happens after the user
 *    actually confirms their email).
 *  - on failure: surface a specific error message
 */
@Inject
class SignUpViewModel(
    private val authRepository: AuthRepository,
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

                val email = current.email.trim()
                val password = current.password
                val isAnonymousGuest =
                    (authRepository.current() as? AuthState.Authenticated)?.isAnonymous == true

                if (isAnonymousGuest) {
                    handleLinkEmail(email, password)
                } else {
                    handleSignUp(email, password)
                }
            }
        }
    }

    private suspend fun SignUpAction.Submit.handleLinkEmail(email: String, password: String) {
        val outcome = authRepository.linkEmailIdentity(email, password)
        when (outcome) {
            is LinkEmailIdentityOutcome.VerificationRequired -> {
                updateState { it.copy(isSubmitting = false) }
                sendEvent(SignUpEvent.NavigateToVerifyEmail(outcome.email))
            }
            is LinkEmailIdentityOutcome.EmailAlreadyRegistered -> updateState {
                it.copy(isSubmitting = false, error = "That email is already in use. Try signing in instead.")
            }
            is LinkEmailIdentityOutcome.WeakPassword -> updateState {
                it.copy(isSubmitting = false, error = "Pick a stronger password (at least ${SignUpState.MIN_PASSWORD_LENGTH} characters).")
            }
            is LinkEmailIdentityOutcome.InvalidEmail -> updateState {
                it.copy(isSubmitting = false, error = "That email doesn't look right.")
            }
            LinkEmailIdentityOutcome.NotAnonymous,
            LinkEmailIdentityOutcome.NotSignedIn -> handleSignUp(email, password)
            is LinkEmailIdentityOutcome.NetworkError -> updateState {
                it.copy(isSubmitting = false, error = "Couldn't reach the server. Check your connection.")
            }
            is LinkEmailIdentityOutcome.Unknown -> updateState {
                it.copy(isSubmitting = false, error = "Sign up failed. Please try again.")
            }
        }
    }

    private suspend fun SignUpAction.Submit.handleSignUp(email: String, password: String) {
        val outcome = authRepository.signUpWithEmail(email = email, password = password)
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
