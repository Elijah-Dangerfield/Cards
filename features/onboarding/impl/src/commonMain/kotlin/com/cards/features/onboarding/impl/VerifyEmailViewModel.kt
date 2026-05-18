package com.dangerfield.cards.features.onboarding.impl

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.IdentityRepository
import com.dangerfield.cards.libraries.identity.RefreshOutcome
import com.dangerfield.cards.libraries.identity.ResendOutcome
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject

/**
 * "Check your email" screen logic. The user has just signed up; Supabase
 * sent them a verification link. We sit on this screen until they
 * confirm.
 *
 * Two user actions matter:
 *  - **I clicked the link** → call `refreshSession()` and check whether
 *    `email_confirmed_at` is now set. If yes, mark onboarded and go to
 *    home. If no, show "still pending" inline.
 *  - **Resend** → call `resendVerificationEmail()`; surface rate-limit
 *    errors so the user knows to wait.
 *
 * No auto-polling for V1 (battery + design choice). The manual button is
 * a clear contract.
 */
@Inject
class VerifyEmailViewModel(
    private val identityRepository: IdentityRepository,
    private val appCache: AppCache,
    @Assisted private val email: String,
) : SEAViewModel<VerifyEmailState, VerifyEmailEvent, VerifyEmailAction>(
    initialStateArg = VerifyEmailState(email = email),
) {

    override suspend fun handleAction(action: VerifyEmailAction) {
        when (action) {
            is VerifyEmailAction.DismissBanner -> action.updateState {
                it.copy(banner = null)
            }

            is VerifyEmailAction.IClickedTheLink -> action.run {
                updateState { it.copy(isRefreshing = true, banner = null) }

                when (val outcome = identityRepository.refreshSession()) {
                    is RefreshOutcome.EmailConfirmed -> {
                        appCache.update { it.copy(hasUserOnboarded = true) }
                        updateState { it.copy(isRefreshing = false) }
                        sendEvent(VerifyEmailEvent.NavigateToHome)
                    }
                    is RefreshOutcome.StillPending -> updateState {
                        it.copy(
                            isRefreshing = false,
                            banner = VerifyEmailState.Banner.StillPending,
                        )
                    }
                    is RefreshOutcome.SessionExpired -> {
                        updateState { it.copy(isRefreshing = false) }
                        sendEvent(VerifyEmailEvent.NavigateBackToSignIn)
                    }
                    is RefreshOutcome.NetworkError -> updateState {
                        it.copy(
                            isRefreshing = false,
                            banner = VerifyEmailState.Banner.NetworkError,
                        )
                    }
                    is RefreshOutcome.Unknown -> updateState {
                        it.copy(
                            isRefreshing = false,
                            banner = VerifyEmailState.Banner.GenericError,
                        )
                    }
                }
            }

            is VerifyEmailAction.Resend -> action.run {
                updateState { it.copy(isResending = true, banner = null) }
                val outcome = identityRepository.resendVerificationEmail(email)
                val banner = when (outcome) {
                    is ResendOutcome.Sent -> VerifyEmailState.Banner.ResendSent
                    is ResendOutcome.RateLimited -> VerifyEmailState.Banner.ResendRateLimited
                    is ResendOutcome.NetworkError -> VerifyEmailState.Banner.NetworkError
                    is ResendOutcome.Unknown -> VerifyEmailState.Banner.GenericError
                }
                updateState { it.copy(isResending = false, banner = banner) }
            }
        }
    }
}

data class VerifyEmailState(
    val email: String,
    val isRefreshing: Boolean = false,
    val isResending: Boolean = false,
    val banner: Banner? = null,
) {
    enum class Banner {
        StillPending,
        ResendSent,
        ResendRateLimited,
        NetworkError,
        GenericError,
    }
}

sealed interface VerifyEmailEvent {
    data object NavigateToHome : VerifyEmailEvent
    data object NavigateBackToSignIn : VerifyEmailEvent
}

sealed interface VerifyEmailAction {
    data object IClickedTheLink : VerifyEmailAction
    data object Resend : VerifyEmailAction
    data object DismissBanner : VerifyEmailAction
}
