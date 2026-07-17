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
            is SignUpAction.ConfirmPasswordChanged -> action.updateState {
                it.copy(confirmPassword = action.value, error = null)
            }
            is SignUpAction.Submit -> action.run {
                val current = state
                // canSubmit already requires confirmPassword == password; the
                // mismatch helper on the confirm field is the live feedback.
                if (!current.canSubmit) return@run

                val email = current.email.trim()
                val password = current.password
                val isAnonymousGuest =
                    (authRepository.current() as? AuthState.Authenticated)?.isAnonymous == true

                updateState { it.copy(isSubmitting = true, error = null) }
                if (isAnonymousGuest) {
                    handleLinkEmail(email, password)
                } else {
                    handleSignUp(email, password)
                }
            }
        }
    }

    private suspend fun SignUpAction.handleLinkEmail(email: String, password: String) {
        val outcome = authRepository.linkEmailIdentity(email, password)
        when (outcome) {
            is LinkEmailIdentityOutcome.VerificationRequired -> {
                updateState { it.copy(isSubmitting = false) }
                sendEvent(SignUpEvent.NavigateToVerifyEmail(outcome.email))
            }
            is LinkEmailIdentityOutcome.EmailAlreadyRegistered -> updateState {
                it.copy(isSubmitting = false, error = SignUpError.EmailAlreadyRegistered)
            }
            is LinkEmailIdentityOutcome.WeakPassword -> updateState {
                it.copy(
                    isSubmitting = false,
                    error = SignUpError.WeakPassword(SignUpState.MIN_PASSWORD_LENGTH),
                )
            }
            is LinkEmailIdentityOutcome.InvalidEmail -> updateState {
                it.copy(isSubmitting = false, error = SignUpError.InvalidEmail)
            }
            LinkEmailIdentityOutcome.NotAnonymous,
            LinkEmailIdentityOutcome.NotSignedIn -> handleSignUp(email, password)
            is LinkEmailIdentityOutcome.NetworkError -> updateState {
                it.copy(isSubmitting = false, error = SignUpError.NetworkError)
            }
            is LinkEmailIdentityOutcome.Timeout -> updateState {
                it.copy(isSubmitting = false, error = SignUpError.Timeout)
            }
            is LinkEmailIdentityOutcome.Unknown -> updateState {
                it.copy(isSubmitting = false, error = SignUpError.Unknown)
            }
        }
    }

    private suspend fun SignUpAction.handleSignUp(email: String, password: String) {
        val outcome = authRepository.signUpWithEmail(email = email, password = password)
        when (outcome) {
            is SignUpOutcome.VerificationRequired -> {
                updateState { it.copy(isSubmitting = false) }
                sendEvent(SignUpEvent.NavigateToVerifyEmail(outcome.email))
            }
            is SignUpOutcome.EmailAlreadyRegistered -> updateState {
                it.copy(isSubmitting = false, error = SignUpError.EmailAlreadyRegistered)
            }
            is SignUpOutcome.WeakPassword -> updateState {
                it.copy(
                    isSubmitting = false,
                    error = SignUpError.WeakPassword(SignUpState.MIN_PASSWORD_LENGTH),
                )
            }
            is SignUpOutcome.InvalidEmail -> updateState {
                it.copy(isSubmitting = false, error = SignUpError.InvalidEmail)
            }
            is SignUpOutcome.NetworkError -> updateState {
                it.copy(isSubmitting = false, error = SignUpError.NetworkError)
            }
            is SignUpOutcome.Timeout -> updateState {
                it.copy(isSubmitting = false, error = SignUpError.Timeout)
            }
            is SignUpOutcome.Unknown -> updateState {
                it.copy(isSubmitting = false, error = SignUpError.Unknown)
            }
        }
    }
}

data class SignUpState(
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val isSubmitting: Boolean = false,
    val error: SignUpError? = null,
) {
    val passwordMismatch: Boolean
        get() = confirmPassword.isNotEmpty() && password != confirmPassword

    val canSubmit: Boolean
        get() = !isSubmitting &&
            email.contains('@') &&
            password.length >= MIN_PASSWORD_LENGTH &&
            confirmPassword == password

    companion object {
        const val MIN_PASSWORD_LENGTH = 6
    }
}

sealed interface SignUpEvent {
    data class NavigateToVerifyEmail(val email: String) : SignUpEvent
}

/**
 * Inline error surfaced under the sign-up form. Typed so the VM doesn't
 * hold raw user-facing copy — `AuthScreens.kt` resolves each variant
 * through Compose Multiplatform resources at render time. `WeakPassword`
 * carries the minimum-length floor so the resource format arg stays
 * server-driven if we ever tune it from the wire.
 */
sealed interface SignUpError {
    data object EmailAlreadyRegistered : SignUpError
    data class WeakPassword(val minLength: Int) : SignUpError
    data object InvalidEmail : SignUpError
    data object NetworkError : SignUpError
    data object Timeout : SignUpError
    data object Unknown : SignUpError
}

sealed interface SignUpAction {
    data class EmailChanged(val value: String) : SignUpAction
    data class PasswordChanged(val value: String) : SignUpAction
    data class ConfirmPasswordChanged(val value: String) : SignUpAction
    data object Submit : SignUpAction
}
