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
            is SignUpAction.DismissError -> action.updateState { it.copy(error = null) }

            is SignUpAction.Submit -> action.run {
                val current = state
                if (!current.canSubmit) return@run
                if (current.password != current.confirmPassword) {
                    updateState { it.copy(error = SignUpError.PasswordsDontMatch) }
                    return@run
                }

                val email = current.email.trim()
                val password = current.password
                val isAnonymousGuest =
                    (authRepository.current() as? AuthState.Authenticated)?.isAnonymous == true

                if (isAnonymousGuest) {
                    // Anonymous guests get a confirm dialog first — the link
                    // permanently attaches their chips/XP/avatars to this
                    // email, and an accidental tap with a typo'd address is
                    // costly to undo. Fresh sign-ups (already-signed-out)
                    // skip the confirm and submit directly.
                    updateState { it.copy(awaitingClaimConfirm = true, error = null) }
                } else {
                    updateState { it.copy(isSubmitting = true, error = null) }
                    handleSignUp(email, password)
                }
            }

            is SignUpAction.DismissClaimConfirm -> action.updateState {
                it.copy(awaitingClaimConfirm = false)
            }

            is SignUpAction.ConfirmClaim -> action.run {
                val current = state
                if (!current.awaitingClaimConfirm) return@run
                updateState {
                    it.copy(awaitingClaimConfirm = false, isSubmitting = true, error = null)
                }
                handleLinkEmail(current.email.trim(), current.password)
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
    /** True while the guest-claim confirm dialog is up; the actual
     *  `linkEmailIdentity` call doesn't fire until the user confirms.
     *  Fresh sign-ups (not anonymous) never reach this state. */
    val awaitingClaimConfirm: Boolean = false,
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
    data object PasswordsDontMatch : SignUpError
    data object EmailAlreadyRegistered : SignUpError
    data class WeakPassword(val minLength: Int) : SignUpError
    data object InvalidEmail : SignUpError
    data object NetworkError : SignUpError
    data object Unknown : SignUpError
}

sealed interface SignUpAction {
    data class EmailChanged(val value: String) : SignUpAction
    data class PasswordChanged(val value: String) : SignUpAction
    data class ConfirmPasswordChanged(val value: String) : SignUpAction
    data object Submit : SignUpAction
    data object DismissError : SignUpAction
    /** User accepted the "this links your guest progress to this email" confirm. */
    data object ConfirmClaim : SignUpAction
    /** User dismissed the confirm without submitting. */
    data object DismissClaimConfirm : SignUpAction
}
