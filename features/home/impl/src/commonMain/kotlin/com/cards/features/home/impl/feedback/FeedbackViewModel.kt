package com.dangerfield.cards.features.home.impl.feedback

import com.dangerfield.cards.libraries.core.eitherWay
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.ui.snackbar.showSnackBar
import me.tatarka.inject.annotations.Inject
import kotlin.time.Duration.Companion.seconds

@Inject
class FeedbackViewModel(
    private val repository: FeedbackRepository,
    private val router: Router,
    private val appCache: AppCache,
) : SEAViewModel<FeedbackState, Unit, FeedbackAction>(
    initialStateArg = FeedbackState()
) {

    override suspend fun handleAction(action: FeedbackAction) {
        when (action) {
            FeedbackAction.Back -> router.goBack()
            is FeedbackAction.MessageChanged -> action.updateMessage()
            FeedbackAction.Submit -> action.submitFeedback()
        }
    }

    private suspend fun FeedbackAction.MessageChanged.updateMessage() {
        val updated = value
        updateState { it.copy(message = updated, errorMessage = null) }
    }

    private suspend fun FeedbackAction.submitFeedback() {
        val current = state
        if (current.message.isBlank()) {
            updateState { it.copy(errorMessage = "Add a quick note before sending.") }
            return
        }
        updateState { it.copy(isSubmitting = true, errorMessage = null) }
        repository.submitFeedback(
            message = current.message.trim(),
            isBugReport = false
        ).eitherWay {
            appCache.update { it.copy(feedbacksGiven = it.feedbacksGiven + 1) }
            updateState { it.copy(isSubmitting = false) }
            showSnackBar(message = "Got it. Thank you!", delayBy = 1.seconds)
            router.goBack()
        }
    }
}

data class FeedbackState(
    val message: String = "",
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
)

sealed interface FeedbackAction {
    data object Back : FeedbackAction
    data class MessageChanged(val value: String) : FeedbackAction
    data object Submit : FeedbackAction
}
