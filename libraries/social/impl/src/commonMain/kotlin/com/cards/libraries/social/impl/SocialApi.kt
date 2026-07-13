package com.dangerfield.cards.libraries.social.impl

import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.networking.authedCall
import com.dangerfield.cards.libraries.networking.retry.RetryPolicy
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Thin HTTP layer over the server's social endpoints. Defined as an interface
 * so the repository tests can fake it without standing up a network stack.
 *
 * All three calls are Bearer-authed with the Supabase JWT; the server resolves
 * the caller from the `sub` claim.
 */
interface SocialApi {
    /** `GET /v1/recent-opponents?limit=N` — bare opponent ids, newest-first. */
    suspend fun recentOpponentIds(limit: Int): List<String>

    /** `GET /v1/profiles?ids=a,b,c` — resolve ids to public profiles. */
    suspend fun resolveProfiles(ids: List<String>): List<PublicProfileDto>

    /** `POST /v1/friends/requests` — send a request. Throws on 4xx. */
    suspend fun sendFriendRequest(userId: String): FriendRequestResultDto

    /** `GET /v1/friends/requests` — bare ids of pending inbound requesters. */
    suspend fun incomingFriendRequestIds(): List<String>

    /** `POST /v1/friends/requests/{id}/accept`. Throws on 4xx. */
    suspend fun acceptFriendRequest(userId: String): FriendRequestResultDto

    /** `POST /v1/friends/requests/{id}/decline`. Throws on 4xx. */
    suspend fun declineFriendRequest(userId: String): FriendRequestResultDto

    /** `POST /v1/reports` — file a report against a player. Throws on 4xx. */
    suspend fun reportPlayer(userId: String, roomCode: String?, reason: String?): PlayerReportResultDto
}

@Serializable
data class RecentOpponentsResponseDto(
    val schemaVersion: Int = 1,
    val opponents: List<String> = emptyList(),
)

@Serializable
data class ProfilesResponseDto(
    val schemaVersion: Int = 1,
    val profiles: List<PublicProfileDto> = emptyList(),
)

@Serializable
data class PublicProfileDto(
    val id: String,
    val displayName: String,
    val avatarEmoji: String,
    val avatarBackgroundColor: String? = null,
)

@Serializable
data class FriendRequestBodyDto(
    val userId: String,
)

@Serializable
data class FriendRequestResultDto(
    val state: String,
)

@Serializable
data class FriendRequestsResponseDto(
    val schemaVersion: Int = 1,
    val requests: List<String> = emptyList(),
)

@Serializable
data class PlayerReportBodyDto(
    val reportedUserId: String,
    val roomCode: String? = null,
    val reason: String? = null,
)

@Serializable
data class PlayerReportResultDto(
    val status: String,
)

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class HttpSocialApi(
    private val networkClient: NetworkClient,
) : SocialApi {

    // GET is read-only — safe to retry.
    override suspend fun recentOpponentIds(limit: Int): List<String> =
        networkClient.authedCall("recentOpponents.fetch", retry = RetryPolicy.idempotent()) { client ->
            client.get("/v1/recent-opponents") {
                parameter("limit", limit)
            }.body<RecentOpponentsResponseDto>()
        }.getOrThrow().opponents

    override suspend fun resolveProfiles(ids: List<String>): List<PublicProfileDto> {
        if (ids.isEmpty()) return emptyList()
        return networkClient.authedCall("profiles.resolve", retry = RetryPolicy.idempotent()) { client ->
            client.get("/v1/profiles") {
                parameter("ids", ids.joinToString(","))
            }.body<ProfilesResponseDto>()
        }.getOrThrow().profiles
    }

    // POST is a mutation — leave retry at None. ClientRequestException on 4xx
    // surfaces so the repository can map 403/400/429 to typed results.
    override suspend fun sendFriendRequest(userId: String): FriendRequestResultDto =
        networkClient.authedCall("friends.request") { client ->
            client.post("/v1/friends/requests") {
                contentType(ContentType.Application.Json)
                setBody(FriendRequestBodyDto(userId))
            }.body<FriendRequestResultDto>()
        }.getOrThrow()

    // GET is read-only — safe to retry.
    override suspend fun incomingFriendRequestIds(): List<String> =
        networkClient.authedCall("friends.requests.fetch", retry = RetryPolicy.idempotent()) { client ->
            client.get("/v1/friends/requests").body<FriendRequestsResponseDto>()
        }.getOrThrow().requests

    // Accept/decline are idempotent on the server (a re-accept is a no-op,
    // a gone request returns 404), so retry is safe.
    override suspend fun acceptFriendRequest(userId: String): FriendRequestResultDto =
        networkClient.authedCall("friends.request.accept", retry = RetryPolicy.idempotent()) { client ->
            client.post("/v1/friends/requests/$userId/accept").body<FriendRequestResultDto>()
        }.getOrThrow()

    override suspend fun declineFriendRequest(userId: String): FriendRequestResultDto =
        networkClient.authedCall("friends.request.decline", retry = RetryPolicy.idempotent()) { client ->
            client.post("/v1/friends/requests/$userId/decline").body<FriendRequestResultDto>()
        }.getOrThrow()

    // POST is a mutation, but filing the same report twice is harmless (append-
    // only, no dedup server-side), so leave retry at None to keep 4xx surfacing
    // as ClientRequestException for the 429 mapping rather than silently retrying.
    override suspend fun reportPlayer(userId: String, roomCode: String?, reason: String?): PlayerReportResultDto =
        networkClient.authedCall("reports.create") { client ->
            client.post("/v1/reports") {
                contentType(ContentType.Application.Json)
                setBody(PlayerReportBodyDto(reportedUserId = userId, roomCode = roomCode, reason = reason))
            }.body<PlayerReportResultDto>()
        }.getOrThrow()
}
