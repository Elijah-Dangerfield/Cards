package com.dangerfield.cards.features.onboarding.impl

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.isiOS
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.AppleSignInEnabled
import com.dangerfield.cards.libraries.identity.GoogleSignInEnabled
import com.dangerfield.cards.libraries.identity.auth.AppleSignInCoordinator
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.identity.auth.SignInOutcome
import com.dangerfield.cards.libraries.identity.auth.awaitCredential
import me.tatarka.inject.annotations.Inject

/**
 * Drives the sign-in screen. On submit:
 *  - validate inputs locally (non-empty email + ≥ 6 char password)
 *  - call `authRepository.signInWithEmail(...)`
 *  - on success: mark onboarded + emit [SignInEvent.NavigateToHome]
 *  - on failure: surface a specific error message
 *
 * No try/catch — the repository returns sealed outcome types so we just
 * pattern-match on the result. `Success` doesn't carry an identity
 * payload — the new auth state is on `AuthRepository.observe()` and the
 * profile follows via `ProfileRepository` automatically.
 */
@Inject
class SignInViewModel(
    private val authRepository: AuthRepository,
    private val appCache: AppCache,
    private val appleSignInCoordinator: AppleSignInCoordinator,
    googleSignInEnabled: GoogleSignInEnabled,
    appleSignInEnabled: AppleSignInEnabled,
) : SEAViewModel<SignInState, SignInEvent, SignInAction>(
    initialStateArg = SignInState(
        googleEnabled = googleSignInEnabled(),
        // Apple is the native flow only, which is iOS-only (see docs/decisions.md).
        appleEnabled = appleSignInEnabled() && BuildInfo.isiOS(),
    ),
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
                handleSignInOutcome(
                    authRepository.signInWithEmail(
                        email = current.email.trim(),
                        password = current.password,
                    ),
                )
            }

            is SignInAction.SignInWithOAuth -> action.run {
                updateState { it.copy(isSubmitting = true, error = null) }
                handleSignInOutcome(authRepository.signInWithOAuth(action.provider))
            }

            is SignInAction.SignInWithApple -> action.run {
                updateState { it.copy(isSubmitting = true, error = null) }
                Catching { appleSignInCoordinator.awaitCredential() }
                    .logOnFailure { "Apple credential request failed (sign-in)" }
                    .fold(
                        onSuccess = { credential ->
                            if (credential == null) {
                                updateState { it.copy(isSubmitting = false) } // dismissed
                            } else {
                                handleSignInOutcome(authRepository.signInWithApple(credential))
                            }
                        },
                        onFailure = {
                            updateState { it.copy(isSubmitting = false, error = SignInError.Unknown) }
                        },
                    )
            }
        }
    }

    /** Receiver is required so [updateState] (defined on `A`) resolves. */
    private suspend fun SignInAction.handleSignInOutcome(outcome: SignInOutcome) {
        when (outcome) {
            is SignInOutcome.Success -> {
                appCache.update { it.copy(hasUserOnboarded = true) }
                updateState { it.copy(isSubmitting = false) }
                sendEvent(SignInEvent.NavigateToHome)
            }
            is SignInOutcome.InvalidCredentials -> updateState {
                it.copy(isSubmitting = false, error = SignInError.InvalidCredentials)
            }
            is SignInOutcome.EmailNotConfirmed -> {
                updateState { it.copy(isSubmitting = false) }
                sendEvent(SignInEvent.NavigateToVerifyEmail(outcome.email))
            }
            is SignInOutcome.NetworkError -> updateState {
                it.copy(isSubmitting = false, error = SignInError.NetworkError)
            }
            is SignInOutcome.Cancelled -> updateState {
                it.copy(isSubmitting = false)
            }
            is SignInOutcome.ProviderNotEnabled -> updateState {
                it.copy(isSubmitting = false, error = SignInError.ProviderNotEnabled)
            }
            is SignInOutcome.Unknown -> updateState {
                it.copy(isSubmitting = false, error = SignInError.Unknown)
            }
        }
    }
}

data class SignInState(
    val email: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val error: SignInError? = null,
    val googleEnabled: Boolean = false,
    val appleEnabled: Boolean = false,
) {
    /** Cheap client-side gate. Server is the canonical validator. */
    val canSubmit: Boolean
        get() = !isSubmitting && email.contains('@') && password.length >= MIN_PASSWORD_LENGTH

    val anyOAuthEnabled: Boolean get() = googleEnabled || appleEnabled

    companion object {
        const val MIN_PASSWORD_LENGTH = 6
    }
}

sealed interface SignInEvent {
    data object NavigateToHome : SignInEvent
    data class NavigateToVerifyEmail(val email: String) : SignInEvent
}

/**
 * Inline error surfaced under the sign-in form. Typed so the VM doesn't
 * hold raw user-facing copy — `AuthScreens.kt` resolves each variant
 * through Compose Multiplatform resources at render time.
 */
sealed interface SignInError {
    data object InvalidCredentials : SignInError
    data object NetworkError : SignInError
    data object ProviderNotEnabled : SignInError
    data object Unknown : SignInError
}

sealed interface SignInAction {
    data class EmailChanged(val value: String) : SignInAction
    data class PasswordChanged(val value: String) : SignInAction
    data object Submit : SignInAction
    data class SignInWithOAuth(val provider: OAuthProvider) : SignInAction
    /** Native "Sign in with Apple" (iOS) — runs the coordinator, then signs in. */
    data object SignInWithApple : SignInAction
    data object DismissError : SignInAction
}
