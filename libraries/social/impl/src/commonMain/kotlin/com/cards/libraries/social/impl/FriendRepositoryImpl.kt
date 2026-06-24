package com.dangerfield.cards.libraries.social.impl

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.social.FriendProfile
import com.dangerfield.cards.libraries.social.FriendRepository
import com.dangerfield.cards.libraries.social.RespondToRequestResult
import com.dangerfield.cards.libraries.social.SendFriendRequestResult
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * The friend graph from the caller's side: the outbound "send a request" path
 * plus the inbound inbox. Maps the server's status codes onto typed results so
 * the caller can flip a tile or surface the right message.
 *
 * The inbox follows the same two-step (ids -> profiles) contract the recently-
 * played-with shelf uses: `GET /v1/friends/requests` returns bare ids and
 * `/v1/profiles` resolves them, so the client never holds a directory of
 * strangers. Accept / decline drop the actioned row optimistically so it leaves
 * the inbox the instant the user taps, with a refresh reconciling on the next
 * entry.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = FriendRepository::class)
@Inject
class FriendRepositoryImpl(
    private val api: SocialApi,
) : FriendRepository {

    private val logger = KLog.withTag("FriendRepository")
    private val incoming = MutableStateFlow<List<FriendProfile>>(emptyList())

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

    override fun observeIncomingRequests(): Flow<List<FriendProfile>> = incoming.asStateFlow()

    override suspend fun refreshIncomingRequests() {
        Catching {
            val ids = api.incomingFriendRequestIds()
            if (ids.isEmpty()) {
                incoming.value = emptyList()
                return@Catching
            }
            val byId = api.resolveProfiles(ids).associateBy { it.id }
            // Preserve the server's newest-first order; drop any id that didn't
            // resolve to a profile rather than render a blank row.
            incoming.value = ids.mapNotNull { id ->
                byId[id]?.let { dto ->
                    FriendProfile(
                        id = dto.id,
                        displayName = dto.displayName,
                        avatarEmoji = dto.avatarEmoji,
                        avatarBackgroundColorHex = dto.avatarBackgroundColor,
                    )
                }
            }
        }.onFailure { logger.w(it) { "incoming friend requests refresh failed" } }
    }

    override suspend fun accept(userId: String): RespondToRequestResult =
        respond(userId) { api.acceptFriendRequest(userId) }

    override suspend fun decline(userId: String): RespondToRequestResult =
        respond(userId) { api.declineFriendRequest(userId) }

    private suspend fun respond(
        userId: String,
        call: suspend () -> Unit,
    ): RespondToRequestResult = Catching { call() }.fold(
        onSuccess = {
            dropFromInbox(userId)
            RespondToRequestResult.Ok
        },
        onFailure = { e ->
            // A 404 means the request is already gone (cancelled by the other
            // side, or actioned on another device) — the row should leave the
            // inbox either way, so treat it as success.
            if (e is ClientRequestException && e.response.status.value == 404) {
                dropFromInbox(userId)
                RespondToRequestResult.Ok
            } else {
                logger.w(e) { "respond to request from $userId failed" }
                RespondToRequestResult.Error(e)
            }
        },
    )

    private fun dropFromInbox(userId: String) {
        incoming.update { current -> current.filterNot { it.id == userId } }
    }
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
