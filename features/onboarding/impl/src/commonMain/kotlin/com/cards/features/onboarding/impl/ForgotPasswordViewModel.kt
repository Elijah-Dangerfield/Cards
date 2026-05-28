package com.dangerfield.cards.features.onboarding.impl

import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.SendResetOutcome
import me.tatarka.inject.annotations.Inject

/**
 * "Forgot password" entry — the user types their email and we ask Supabase
 * to send a reset link. The recovery deep-link target (where the user
 * actually picks a new password) lives in a follow-up.
 *
 * On submit:
 *  - validate inputs locally (non-empty email containing '@')
 *  - call `authRepository.sendPasswordResetEmail(...)`
 *  - on [SendResetOutcome.Sent]: flip [ForgotPasswordState.sent] so the
 *    screen renders the "Check your inbox" success copy
 *  - on rate-limit / network / unknown failures: surface a typed banner
 *
 * No account-existence signal — Supabase returns success for unknown
 * emails too, so the UI shows "Sent" regardless. That's the account-
 * enumeration mitigation, not a bug.
 */
@Inject
class ForgotPasswordViewModel(
    private val authRepository: AuthRepository,
) : SEAViewModel<ForgotPasswordState, ForgotPasswordEvent, ForgotPasswordAction>(
    initialStateArg = ForgotPasswordState(),
) {

    override suspend fun handleAction(action: ForgotPasswordAction) {
        when (action) {
            is ForgotPasswordAction.EmailChanged -> action.updateState {
                it.copy(email = action.value, banner = null)
            }
            is ForgotPasswordAction.DismissBanner -> action.updateState {
                it.copy(banner = null)
            }

            is ForgotPasswordAction.Submit -> action.run {
                val current = state
                if (!current.canSubmit) return@run

                updateState { it.copy(isSubmitting = true, banner = null) }
                val outcome = authRepository.sendPasswordResetEmail(current.email.trim())
                when (outcome) {
                    is SendResetOutcome.Sent -> updateState {
                        it.copy(isSubmitting = false, sent = true, banner = null)
                    }
                    is SendResetOutcome.RateLimited -> updateState {
                        it.copy(
                            isSubmitting = false,
                            banner = ForgotPasswordState.Banner.RateLimited,
                        )
                    }
                    is SendResetOutcome.NetworkError -> updateState {
                        it.copy(
                            isSubmitting = false,
                            banner = ForgotPasswordState.Banner.NetworkError,
                        )
                    }
                    is SendResetOutcome.Unknown -> updateState {
                        it.copy(
                            isSubmitting = false,
                            banner = ForgotPasswordState.Banner.GenericError,
                        )
                    }
                }
            }
        }
    }
}

data class ForgotPasswordState(
    val email: String = "",
    val isSubmitting: Boolean = false,
    val sent: Boolean = false,
    val banner: Banner? = null,
) {
    val canSubmit: Boolean
        get() = !isSubmitting && !sent && email.contains('@')

    enum class Banner {
        RateLimited,
        NetworkError,
        GenericError,
    }
}

sealed interface ForgotPasswordEvent

sealed interface ForgotPasswordAction {
    data class EmailChanged(val value: String) : ForgotPasswordAction
    data object Submit : ForgotPasswordAction
    data object DismissBanner : ForgotPasswordAction
}
