package com.dangerfield.cards.libraries.identity.impl

import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.networking.authedCall
import com.dangerfield.cards.libraries.networking.unauthedCall
import com.dangerfield.cards.libraries.networking.retry.RetryPolicy
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
 * Supabase JWT produced by `AuthRepository.accessToken()`. The server
 * validates that JWT and treats us as the user whose `sub` claim it
 * carries.
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

    /**
     * `GET /v1/avatars` — emoji packs available to the caller.
     * Always includes the starter pack; premium packs appear once the
     * matching product is in inventory.
     */
    suspend fun avatars(): AvatarPackResponseDto

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
    val avatarBackgroundColor: String? = null,
    /**
     * Tri-state on the wire: setting a non-null `avatarBackgroundColor`
     * picks it; setting `clearAvatarBackgroundColor=true` clears it back
     * to the theme default. Both fields can't usefully be set together.
     */
    val clearAvatarBackgroundColor: Boolean = false,
)

@Serializable
data class AvatarPackResponseDto(
    val packs: List<AvatarPackDto>,
    val backgroundPalette: List<String> = emptyList(),
)

@Serializable
data class AvatarPackDto(
    val id: String,
    val name: String,
    val emojis: List<String>,
    /**
     * Server-side product id that unlocks this pack. `null` for the
     * starter pack (always available). Premium packs return this so the
     * client can filter the picker against local inventory without
     * waiting on a server-side inventory join — newly-purchased packs
     * show up the moment the optimistic local row is written.
     */
    val unlockProductId: String? = null,
)

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class HttpProfileApi(
    private val networkClient: NetworkClient,
) : ProfileApi {

    // GET /v1/me is server-side get-or-create — same userId returns the
    // same row, replays are no-ops. Safe to retry. This is the call that
    // hung for 30s on Fly cold-boot in production logs and dumped users
    // into Profile.Fallback; the retry covers that case.
    override suspend fun me(): MeDto =
        networkClient.authedCall("me.fetch", retry = RetryPolicy.idempotent()) { client ->
            client.get("/v1/me").body<MeDto>()
        }.getOrThrow()

    // PATCH is a write — leave retry at None. ClientRequestException on
    // 4xx still surfaces so the repository can map 409 → name-taken etc.
    override suspend fun patchMe(request: PatchMeRequest): MeDto =
        networkClient.authedCall("me.patch") { client ->
            client.patch("/v1/me") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body<MeDto>()
        }.getOrThrow()

    // GET /v1/avatars is public — server is anonymous on this route so
    // the response is identical for every caller, and the fetch can
    // race out at process start without waiting on the Supabase JWT.
    // That's the whole point: on a cold install with a slow auth
    // bootstrap, the starter pack still lands before onboarding's
    // picker renders. Read-only — safe to retry.
    override suspend fun avatars(): AvatarPackResponseDto =
        networkClient.unauthedCall("avatars.fetch", retry = RetryPolicy.idempotent()) { client ->
            client.get("/v1/avatars").body<AvatarPackResponseDto>()
        }.getOrThrow()

    // DELETE /v1/me is one-shot. No retry; the auth repo branches on the
    // raw HttpResponse status. Caller wraps in its own Catching to do that.
    override suspend fun deleteMe(): HttpResponse =
        networkClient.authedCall("me.delete") { client ->
            client.delete("/v1/me")
        }.getOrThrow()
}
