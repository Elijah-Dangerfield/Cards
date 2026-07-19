package com.dangerfield.cards.features.profile.impl.feedback

import com.dangerfield.cards.libraries.cards.Telemetry
import com.dangerfield.cards.libraries.core.Catching
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
        email: String? = null,
        screenshots: List<ByteArray> = emptyList(),
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
        email: String?,
        screenshots: List<ByteArray>,
    ): Result<Unit> {
        return Catching {
            telemetry.captureUserFeedback(
                message = message,
                isBugReport = isBugReport,
                eventId = logId,
                errorCode = errorCode,
                email = email,
                screenshots = screenshots,
            )
        }
    }
}
