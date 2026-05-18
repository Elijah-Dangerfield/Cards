package com.dangerfield.cards.libraries.identity.impl

import com.dangerfield.cards.libraries.networking.NetworkClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Thin HTTP layer for our own server's profile endpoint. Defined as an
 * interface so impl-module tests can fake it trivially.
 *
 * Uses [NetworkClient.authenticatedClient] — the Bearer token is the
 * Supabase JWT held by [SupabaseAuthTokenProvider]. The server validates
 * that JWT and treats us as the user whose `sub` claim it carries.
 */
interface ProfileApi {
    /** `GET /v1/me` — server is get-or-create. */
    suspend fun me(): MeDto

    /**
     * `PATCH /v1/me` — partial update. Returns the new profile on 2xx;
     * throws `ClientRequestException` on 4xx so the repository can map
     * 409 to "name taken," 400 to validation, etc.
     */
    suspend fun patchMe(request: PatchMeRequest): MeDto

    /** `GET /v1/avatars` — starter emoji pack for the picker. */
    suspend fun avatars(): AvatarPackDto

    /**
     * `DELETE /v1/me` — permanent account deletion. Returns the raw
     * response so the repository can branch on 204 (success), 503
     * (delete_not_configured), 401 (session gone), etc.
     */
    suspend fun deleteMe(): HttpResponse
}

@Serializable
data class PatchMeRequest(
    val displayName: String? = null,
    val avatarEmoji: String? = null,
)

@Serializable
data class AvatarPackDto(
    val starter: List<String>,
)

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class HttpProfileApi(
    private val networkClient: NetworkClient,
) : ProfileApi {
    override suspend fun me(): MeDto =
        networkClient.authenticatedClient.get("/v1/me").body()

    override suspend fun patchMe(request: PatchMeRequest): MeDto =
        networkClient.authenticatedClient.patch("/v1/me") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    override suspend fun avatars(): AvatarPackDto =
        networkClient.authenticatedClient.get("/v1/avatars").body()

    override suspend fun deleteMe(): HttpResponse =
        networkClient.authenticatedClient.delete("/v1/me")
}
