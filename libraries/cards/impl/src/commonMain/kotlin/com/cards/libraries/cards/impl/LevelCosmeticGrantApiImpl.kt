package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.LevelCosmeticGrantApi
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
 * HTTP impl of [LevelCosmeticGrantApi]. Single endpoint:
 * `POST /v1/me/grants/level-cosmetic/{productId}` — server checks its
 * level-grant allowlist, returns 200 (granted) or 204/403 (not grantable /
 * unknown product).
 *
 * Idempotent retry: the server's `recordEarnedGrant` is first-grant-wins on
 * `(userId, productId)`, so a retried POST returns the same row. Marked
 * [RetryPolicy.idempotent] so transient network errors don't drop the grant.
 *
 * Never throws — failure logs at warn and returns false. The level-up grant
 * flow upstream must keep working when the call can't reach the server; the
 * next launch / app-foreground inventory sync catches anything missed.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class LevelCosmeticGrantApiImpl(
    private val networkClient: NetworkClient,
) : LevelCosmeticGrantApi {

    private val logger = KLog.withTag("LevelCosmeticGrant")

    override suspend fun grantLevelCosmetic(productId: String): Boolean {
        val result: Catching<Boolean> = networkClient.authedCall(
            description = "grants.levelCosmetic",
            retry = RetryPolicy.idempotent(),
        ) { client ->
            val response: HttpResponse = client.post(
                "/v1/me/grants/level-cosmetic/$productId",
            )
            when (response.status) {
                HttpStatusCode.OK -> true
                HttpStatusCode.NoContent -> false
                else -> {
                    logger.w {
                        "Unexpected status ${response.status.value} for $productId"
                    }
                    false
                }
            }
        }
        return result.getOrDefault(false)
    }
}
