package com.dangerfield.cards.features.profile.impl.account

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.DeleteAccountOutcome
import kotlinx.coroutines.async
import me.tatarka.inject.annotations.Inject

/**
 * Type-to-confirm account deletion. Apple App Store review explicitly
 * requires a non-trivial confirmation step for destructive actions like
 * deletion ([App Store Guideline 5.1.1(v)](https://developer.apple.com/app-store/review/guidelines/#5.1.1));
 * matching the requirement on Android keeps both platforms aligned.
 *
 * On success the VM clears local onboarding state so the next launch
 * shows the intro pager again, and emits [DeleteAccountEvent.Deleted] —
 * the entry point watches for this and routes to the onboarding root.
 *
 * On failure the user stays on the screen with a state-specific message;
 * the input value is preserved so they don't have to retype "delete."
 */
@Inject
class DeleteAccountViewModel(
    private val authRepository: AuthRepository,
    private val appCache: AppCache,
    private val appScope: AppCoroutineScope,
) : SEAViewModel<DeleteAccountState, DeleteAccountEvent, DeleteAccountAction>(
    initialStateArg = DeleteAccountState(),
) {

    override suspend fun handleAction(action: DeleteAccountAction) {
        when (action) {
            is DeleteAccountAction.ConfirmationChanged -> action.updateState {
                it.copy(confirmationInput = action.value, error = null)
            }

            is DeleteAccountAction.DismissError -> action.updateState { it.copy(error = null) }

            is DeleteAccountAction.ConfirmDelete -> action.run {
                val current = state
                if (!current.canSubmit) return@run

                updateState { it.copy(isSubmitting = true, error = null) }

                val outcome = appScope.async { authRepository.deleteAccount() }.await()
                when (outcome) {
                    is DeleteAccountOutcome.Success -> {
                        appCache.update { it.copy(hasUserOnboarded = false) }
                        updateState { it.copy(isSubmitting = false) }
                        sendEvent(DeleteAccountEvent.Deleted)
                    }
                    is DeleteAccountOutcome.NotConfigured -> updateState {
                        it.copy(
                            isSubmitting = false,
                            error = DeleteAccountError.NotConfigured,
                        )
                    }
                    is DeleteAccountOutcome.NotSignedIn -> {
                        // Local session is gone — treat as success from the user's
                        // POV (the data they wanted deleted is no longer
                        // referenceable). Bounce to onboarding.
                        appCache.update { it.copy(hasUserOnboarded = false) }
                        updateState { it.copy(isSubmitting = false) }
                        sendEvent(DeleteAccountEvent.Deleted)
                    }
                    is DeleteAccountOutcome.NetworkError -> updateState {
                        it.copy(
                            isSubmitting = false,
                            error = DeleteAccountError.NetworkError,
                        )
                    }
                    is DeleteAccountOutcome.Unknown -> updateState {
                        it.copy(
                            isSubmitting = false,
                            error = DeleteAccountError.Unknown,
                        )
                    }
                }
            }
        }
    }
}

data class DeleteAccountState(
    val confirmationInput: String = "",
    val isSubmitting: Boolean = false,
    val error: DeleteAccountError? = null,
) {
    val isConfirmationValid: Boolean
        get() = confirmationInput.trim().equals(REQUIRED_PHRASE, ignoreCase = true)

    val canSubmit: Boolean
        get() = !isSubmitting && isConfirmationValid

    companion object {
        /** The user must type this (case-insensitive) before the delete button enables. */
        const val REQUIRED_PHRASE = "delete"
    }
}

sealed interface DeleteAccountError {
    data object NotConfigured : DeleteAccountError
    data object NetworkError : DeleteAccountError
    data object Unknown : DeleteAccountError
}

sealed interface DeleteAccountEvent {
    data object Deleted : DeleteAccountEvent
}

sealed interface DeleteAccountAction {
    data class ConfirmationChanged(val value: String) : DeleteAccountAction
    data object ConfirmDelete : DeleteAccountAction
    data object DismissError : DeleteAccountAction
}
