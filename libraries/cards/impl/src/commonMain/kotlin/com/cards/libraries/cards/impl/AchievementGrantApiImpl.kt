package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AchievementGrantApi
import com.dangerfield.cards.libraries.cards.AchievementId
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.networking.authedCall
import com.dangerfield.cards.libraries.networking.retry.RetryPolicy
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * HTTP impl of [AchievementGrantApi]. Single endpoint:
 * `POST /v1/me/grants/achievement/{achievementId}` — server resolves any
 * mapping, returns 200 (granted) or 204 (no reward / unknown id).
 *
 * Idempotent retry: the server's `recordEarnedGrant` is first-grant-wins
 * on `(userId, productId)`, so a retried POST returns the same row. Marks
 * the call with `RetryPolicy.idempotent()` so transient network errors
 * don't drop the grant on the floor.
 *
 * Never throws — failure logs at warn and returns false. The
 * achievement-recording flow upstream must keep working when the grant
 * call can't reach the server; the next launch / app-foreground will
 * re-trigger an inventory sync that catches anything missed.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AchievementGrantApiImpl(
    private val networkClient: NetworkClient,
) : AchievementGrantApi {

    private val logger = KLog.withTag("AchievementGrant")

    override suspend fun grantAchievement(achievementId: AchievementId): Boolean {
        val result: Catching<Boolean> = networkClient.authedCall(
            description = "grants.achievement.${achievementId.name}",
            retry = RetryPolicy.idempotent(),
        ) { client ->
            val response: HttpResponse = client.post(
                "/v1/me/grants/achievement/${achievementId.name}",
            )
            when (response.status) {
                HttpStatusCode.OK -> true
                HttpStatusCode.NoContent -> false
                else -> {
                    logger.w {
                        "Unexpected status ${response.status.value} for ${achievementId.name}"
                    }
                    false
                }
            }
        }
        return result.getOrDefault(false)
    }
}
