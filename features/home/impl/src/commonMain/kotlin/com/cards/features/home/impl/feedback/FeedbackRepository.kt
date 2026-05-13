package com.dangerfield.cards.features.home.impl.feedback

import com.dangerfield.cards.libraries.cards.Telemetry
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

interface FeedbackRepository {
    suspend fun submitFeedback(
        message: String,
        isBugReport: Boolean,
        logId: String? = null,
        errorCode: Int? = null,
    ): Result<Unit>
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class FeedbackRepositoryImpl @Inject constructor(
    private val telemetry: Telemetry
) : FeedbackRepository {
    override suspend fun submitFeedback(
        message: String,
        isBugReport: Boolean,
        logId: String?,
        errorCode: Int?,
    ): Result<Unit> {
        return runCatching {
            telemetry.captureUserFeedback(
                message = message,
                isBugReport = isBugReport,
                eventId = logId,
                errorCode = errorCode
            )
        }
    }
}
