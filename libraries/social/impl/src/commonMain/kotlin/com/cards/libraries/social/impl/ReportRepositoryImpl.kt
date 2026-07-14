package com.dangerfield.cards.libraries.social.impl

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.social.ReportPlayerResult
import com.dangerfield.cards.libraries.social.ReportRepository
import io.ktor.client.plugins.ClientRequestException
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Files player reports over `POST /v1/reports`. Maps the server's `429` onto a
 * typed rate-limit result so the UI can say "try again later"; everything else
 * that fails is a generic error. Success is fire-and-forget — there's nothing
 * to observe afterward.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = ReportRepository::class)
@Inject
class ReportRepositoryImpl(
    private val api: SocialApi,
) : ReportRepository {

    private val logger = KLog.withTag("ReportRepository")

    override suspend fun reportPlayer(
        userId: String,
        roomCode: String?,
        reason: String?,
    ): ReportPlayerResult = Catching { api.reportPlayer(userId, roomCode, reason) }.fold(
        onSuccess = { ReportPlayerResult.Reported },
        onFailure = { e ->
            logger.w(e) { "reportPlayer failed for $userId" }
            if (e is ClientRequestException && e.response.status.value == 429) {
                ReportPlayerResult.RateLimited
            } else {
                ReportPlayerResult.Error(e)
            }
        },
    )
}
