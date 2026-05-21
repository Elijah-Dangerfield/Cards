package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.networking.NetworkClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
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
 * Thin HTTP layer for /v1/rooms. Defined as an interface so impl tests
 * can swap in a fake without spinning up a Ktor MockEngine.
 *
 * Returns raw [HttpResponse] for status-aware mapping at the repo
 * layer — outcome enums (Full, NotFound, etc.) live one layer up so
 * this stays decode-only.
 */
interface RoomApi {
    suspend fun create(request: CreateRoomRequestDto): HttpResponse
    suspend fun join(code: String): HttpResponse
    suspend fun leave(code: String): HttpResponse
    suspend fun fetch(code: String): HttpResponse
    suspend fun listActive(): HttpResponse
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class HttpRoomApi(
    private val networkClient: NetworkClient,
) : RoomApi {

    override suspend fun create(request: CreateRoomRequestDto): HttpResponse =
        networkClient.authenticatedClient.post("/v1/rooms") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    override suspend fun join(code: String): HttpResponse =
        networkClient.authenticatedClient.post("/v1/rooms/$code/join") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

    override suspend fun leave(code: String): HttpResponse =
        networkClient.authenticatedClient.delete("/v1/rooms/$code/me")

    override suspend fun fetch(code: String): HttpResponse =
        networkClient.authenticatedClient.get("/v1/rooms/$code")

    override suspend fun listActive(): HttpResponse =
        networkClient.authenticatedClient.get("/v1/me/active-rooms")
}

internal suspend inline fun <reified T> HttpResponse.bodyOrNull(): T? = try {
    body<T>()
} catch (_: Throwable) {
    null
}
