package com.dangerfield.cards.features.profile.impl.bugreport

import com.dangerfield.cards.features.profile.impl.feedback.FeedbackRepository
import com.dangerfield.cards.libraries.core.eitherWay
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.identity.IdentityRepository
import com.dangerfield.cards.libraries.identity.currentIdentity
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.ui.snackbar.showSnackBar
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject
import kotlin.time.Duration.Companion.seconds

@Inject
class BugReportViewModel(
    private val repository: FeedbackRepository,
    private val router: Router,
    private val appCache: AppCache,
    identityRepository: IdentityRepository,
    @Assisted logId: String? = null,
    @Assisted errorCode: Int? = null,
    @Assisted contextMessage: String? = null,
) : SEAViewModel<BugReportState, Unit, BugReportAction>(
    initialStateArg = BugReportState(
        logId = logId,
        errorCode = errorCode,
        contextMessage = contextMessage,
        email = identityRepository.currentIdentity?.email.orEmpty(),
    )
) {

    override suspend fun handleAction(action: BugReportAction) {
        when (action) {
            BugReportAction.Back -> router.goBack()
            is BugReportAction.MessageChanged -> action.updateMessage()
            is BugReportAction.EmailChanged -> action.updateEmail()
            BugReportAction.Submit -> action.submitBugReport()
        }
    }

    private suspend fun BugReportAction.MessageChanged.updateMessage() {
        val updated = value
        updateState { it.copy(message = updated, errorMessage = null) }
    }

    private suspend fun BugReportAction.EmailChanged.updateEmail() {
        updateState { it.copy(email = value) }
    }

    private suspend fun BugReportAction.submitBugReport() {
        val current = state
        if (current.message.isBlank()) {
            updateState { it.copy(errorMessage = "Add a quick note before sending.") }
            return
        }
        updateState { it.copy(isSubmitting = true, errorMessage = null) }
        repository.submitFeedback(
            message = current.message.trim(),
            isBugReport = true,
            logId = current.logId,
            errorCode = current.errorCode,
            email = current.email.takeIf { it.isNotBlank() },
        ).eitherWay {
            appCache.update { it.copy(bugsReported = it.bugsReported + 1) }
            updateState { it.copy(isSubmitting = false) }
            showSnackBar(message = "Thanks for reporting this!", delayBy = 1.seconds)
            router.goBack()
        }
    }
}

data class BugReportState(
    val message: String = "",
    val email: String = "",
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val logId: String? = null,
    val errorCode: Int? = null,
    val contextMessage: String? = null,
) {
    val hasContext: Boolean
        get() = !contextMessage.isNullOrBlank() || !logId.isNullOrBlank() || errorCode != null
}

sealed interface BugReportAction {
    data object Back : BugReportAction
    data class MessageChanged(val value: String) : BugReportAction
    data class EmailChanged(val value: String) : BugReportAction
    data object Submit : BugReportAction

}
