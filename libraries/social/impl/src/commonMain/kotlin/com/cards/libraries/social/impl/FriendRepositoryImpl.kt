package com.dangerfield.cards.libraries.social.impl

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.social.FriendRepository
import com.dangerfield.cards.libraries.social.SendFriendRequestResult
import io.ktor.client.plugins.ClientRequestException
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Outbound friend-request half of the friend graph. Maps the server's status
 * codes onto [SendFriendRequestResult] so the caller can flip the tile or
 * surface the right message.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = FriendRepository::class)
@Inject
class FriendRepositoryImpl(
    private val api: SocialApi,
) : FriendRepository {

    private val logger = KLog.withTag("FriendRepository")

    override suspend fun sendRequest(userId: String): SendFriendRequestResult =
        Catching { api.sendFriendRequest(userId) }.fold(
            onSuccess = { result ->
                when (result.state) {
                    "accepted" -> SendFriendRequestResult.Accepted
                    else -> SendFriendRequestResult.Requested
                }
            },
            onFailure = { e ->
                logger.w(e) { "sendRequest failed for $userId" }
                e.toSendFriendRequestResult()
            },
        )
}

/**
 * Maps a thrown request failure to a typed result. `403` is the
 * recently-played-with gate (and a blocked pair — conflated; block UI isn't
 * built), `400` a self-request, `429` the rate limit. Anything else (network,
 * 5xx) is a generic [SendFriendRequestResult.Error].
 */
internal fun Throwable.toSendFriendRequestResult(): SendFriendRequestResult =
    when (this) {
        is ClientRequestException -> when (response.status.value) {
            403 -> SendFriendRequestResult.NotPlayedWith
            400 -> SendFriendRequestResult.SelfRequest
            429 -> SendFriendRequestResult.RateLimited
            else -> SendFriendRequestResult.Error(this)
        }
        else -> SendFriendRequestResult.Error(this)
    }
