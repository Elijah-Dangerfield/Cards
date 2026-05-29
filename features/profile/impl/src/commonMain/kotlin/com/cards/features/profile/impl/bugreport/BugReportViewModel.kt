package com.dangerfield.cards.features.profile.impl.bugreport

import androidx.lifecycle.viewModelScope
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.profile_bug_snackbar_thanks
import com.dangerfield.cards.features.profile.impl.feedback.FeedbackRepository
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
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject
import org.jetbrains.compose.resources.getString
import kotlin.time.Duration.Companion.seconds

@Inject
class BugReportViewModel(
    private val repository: FeedbackRepository,
    private val router: Router,
    private val appCache: AppCache,
    private val appScope: AppCoroutineScope,
    private val profileRepository: ProfileRepository,
    @Assisted logId: String? = null,
    @Assisted errorCode: Int? = null,
    @Assisted contextMessage: String? = null,
) : SEAViewModel<BugReportState, Unit, BugReportAction>(
    initialStateArg = BugReportState(
        logId = logId,
        errorCode = errorCode,
        contextMessage = contextMessage,
    ),
) {

    init {
        viewModelScope.launch {
            val current = profileRepository.current() as? Profile.Authenticated
            val email = current?.email.orEmpty()
            if (email.isNotEmpty()) {
                takeAction(BugReportAction.EmailChanged(email))
            }
        }
    }

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
            updateState { it.copy(errorMessage = BugReportError.MessageRequired) }
            return
        }
        updateState { it.copy(isSubmitting = true, errorMessage = null) }
        appScope.async {
            repository.submitFeedback(
                message = current.message.trim(),
                isBugReport = true,
                logId = current.logId,
                errorCode = current.errorCode,
                email = current.email.takeIf { it.isNotBlank() },
            )
        }.await().eitherWay {
            appCache.update { it.copy(bugsReported = it.bugsReported + 1) }
            updateState { it.copy(isSubmitting = false) }
            showSnackBar(
                message = getString(Res.string.profile_bug_snackbar_thanks),
                delayBy = 1.seconds,
            )
            router.goBack()
        }
    }
}

data class BugReportState(
    val message: String = "",
    val email: String = "",
    val isSubmitting: Boolean = false,
    val errorMessage: BugReportError? = null,
    val logId: String? = null,
    val errorCode: Int? = null,
    val contextMessage: String? = null,
) {
    val hasContext: Boolean
        get() = !contextMessage.isNullOrBlank() || !logId.isNullOrBlank() || errorCode != null
}

sealed interface BugReportError {
    data object MessageRequired : BugReportError
}

sealed interface BugReportAction {
    data object Back : BugReportAction
    data class MessageChanged(val value: String) : BugReportAction
    data class EmailChanged(val value: String) : BugReportAction
    data object Submit : BugReportAction

}
