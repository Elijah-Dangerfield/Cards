package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.networking.authedCall
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Thin HTTP layer for /v1/matchmaking — the sibling of [RoomApi], kept separate
 * so the room CRUD surface stays focused. Returns raw [HttpResponse] for
 * status-aware mapping in [MatchmakingRepositoryImpl].
 *
 * Routes through [NetworkClient.authedCall] for the auth pre-flight + structured
 * failure logging, same as [RoomApi]. No retry: a find / play-bots is a user
 * action whose outcome we map deterministically (a 429 should surface as
 * back-off, not be silently retried into the rate limit).
 */
interface MatchmakingApi {
    suspend fun findTable(request: MatchmakingFindRequestDto): HttpResponse
    suspend fun playBots(code: String): HttpResponse
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class HttpMatchmakingApi(
    private val networkClient: NetworkClient,
) : MatchmakingApi {

    override suspend fun findTable(request: MatchmakingFindRequestDto): HttpResponse =
        networkClient.authedCall("matchmaking.find") { client ->
            client.post("/v1/matchmaking/find") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }.getOrThrow()

    override suspend fun playBots(code: String): HttpResponse =
        networkClient.authedCall("matchmaking.playBots") { client ->
            client.post("/v1/matchmaking/$code/play-bots") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
        }.getOrThrow()
}
