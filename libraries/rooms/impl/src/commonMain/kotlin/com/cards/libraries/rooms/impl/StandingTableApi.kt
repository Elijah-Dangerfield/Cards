package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.networking.authedCall
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

interface StandingTableApi {
    suspend fun mine(): HttpResponse
    suspend fun create(request: CreateStandingTableRequestDto): HttpResponse
    suspend fun get(code: String): HttpResponse
    suspend fun update(code: String, request: UpdateStandingTableRequestDto): HttpResponse
    suspend fun archive(code: String): HttpResponse
    suspend fun leave(code: String): HttpResponse
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class HttpStandingTableApi(private val networkClient: NetworkClient) : StandingTableApi {
    override suspend fun mine() = networkClient.authedCall("standing_tables.mine") {
        it.get("/v1/standing-tables/mine")
    }.getOrThrow()

    override suspend fun create(request: CreateStandingTableRequestDto) = networkClient.authedCall("standing_tables.create") {
        it.post("/v1/standing-tables") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }.getOrThrow()

    override suspend fun get(code: String) = networkClient.authedCall("standing_tables.get") {
        it.get("/v1/standing-tables/$code")
    }.getOrThrow()

    override suspend fun update(code: String, request: UpdateStandingTableRequestDto) = networkClient.authedCall("standing_tables.update") {
        it.patch("/v1/standing-tables/$code") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }.getOrThrow()

    override suspend fun archive(code: String) = networkClient.authedCall("standing_tables.archive") {
        it.delete("/v1/standing-tables/$code")
    }.getOrThrow()

    override suspend fun leave(code: String) = networkClient.authedCall("standing_tables.leave") {
        it.delete("/v1/standing-tables/$code/regulars/me")
    }.getOrThrow()
}
