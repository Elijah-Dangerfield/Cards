package com.dangerfield.cards.features.profile.impl.feedback

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.core.eitherWay
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.ui.snackbar.showSnackBar
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import kotlin.time.Duration.Companion.seconds

@Inject
class FeedbackViewModel(
    private val repository: FeedbackRepository,
    private val router: Router,
    private val appCache: AppCache,
    private val appScope: AppCoroutineScope,
    private val profileRepository: ProfileRepository,
) : SEAViewModel<FeedbackState, Unit, FeedbackAction>(
    initialStateArg = FeedbackState(),
) {

    init {
        // Seed email from the authenticated profile once it resolves.
        // Fallback profiles have no email, so we just leave the field
        // empty in that branch and let the user type one in.
        viewModelScope.launch {
            val current = profileRepository.current() as? Profile.Authenticated
            val email = current?.email.orEmpty()
            if (email.isNotEmpty()) {
                takeAction(FeedbackAction.EmailChanged(email))
            }
        }
    }

    override suspend fun handleAction(action: FeedbackAction) {
        when (action) {
            FeedbackAction.Back -> router.goBack()
            is FeedbackAction.MessageChanged -> action.updateMessage()
            is FeedbackAction.EmailChanged -> action.updateEmail()
            FeedbackAction.Submit -> action.submitFeedback()
        }
    }

    private suspend fun FeedbackAction.MessageChanged.updateMessage() {
        val updated = value
        updateState { it.copy(message = updated, errorMessage = null) }
    }

    private suspend fun FeedbackAction.EmailChanged.updateEmail() {
        updateState { it.copy(email = value) }
    }

    private suspend fun FeedbackAction.submitFeedback() {
        val current = state
        if (current.message.isBlank()) {
            updateState { it.copy(errorMessage = "Add a quick note before sending.") }
            return
        }
        updateState { it.copy(isSubmitting = true, errorMessage = null) }
        appScope.async {
            repository.submitFeedback(
                message = current.message.trim(),
                isBugReport = false,
                email = current.email.takeIf { it.isNotBlank() },
            )
        }.await().eitherWay {
            appCache.update { it.copy(feedbacksGiven = it.feedbacksGiven + 1) }
            updateState { it.copy(isSubmitting = false) }
            showSnackBar(message = "Got it. Thank you!", delayBy = 1.seconds)
            router.goBack()
        }
    }
}

data class FeedbackState(
    val message: String = "",
    val email: String = "",
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
)

sealed interface FeedbackAction {
    data object Back : FeedbackAction
    data class MessageChanged(val value: String) : FeedbackAction
    data class EmailChanged(val value: String) : FeedbackAction
    data object Submit : FeedbackAction
}
