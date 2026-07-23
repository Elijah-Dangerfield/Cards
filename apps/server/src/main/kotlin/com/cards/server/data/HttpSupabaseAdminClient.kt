package com.dangerfield.cards.server.data

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.server.config.SupabaseConfig
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.DeleteUserResult
import com.dangerfield.cards.server.domain.SupabaseAdminClient
import com.dangerfield.cards.server.domain.UpdateDisplayNameResult
import com.dangerfield.cards.server.domain.UserId
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import org.slf4j.LoggerFactory
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.util.UUID
import kotlin.time.Instant

/**
 * Talks to `<projectUrl>/auth/v1/admin/users/<id>` using the service-role
 * JWT. Lazily constructs the underlying Ktor client so an unconfigured
 * server (no `SUPABASE_SERVICE_ROLE_KEY`) never opens a connection — every
 * call short-circuits to [DeleteUserResult.NotConfigured].
 *
 * The CIO engine is fine here — small payload, no streaming, no need for
 * connection pooling beyond the default. Tests inject [HttpClientEngine]
 * via the secondary constructor to swap in Ktor's `MockEngine`.
 *
 * **Never log the service-role key.** The boot warning + this kdoc are the
 * only places the constant should be referenced.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
class HttpSupabaseAdminClient(
    private val config: SupabaseConfig,
    engine: HttpClientEngine?,
) : SupabaseAdminClient {

    @Inject
    constructor(config: SupabaseConfig) : this(config = config, engine = null)

    private val client: HttpClient by lazy {
        if (engine != null) {
            HttpClient(engine) { install(ContentNegotiation) { json(JSON_LENIENT) } }
        } else {
            HttpClient(CIO) { install(ContentNegotiation) { json(JSON_LENIENT) } }
        }
    }

    init {
        if (config.serviceRoleKey.isNullOrBlank()) {
            LoggerFactory.getLogger(HttpSupabaseAdminClient::class.java).warn(
                "SUPABASE_SERVICE_ROLE_KEY is not set — admin-gated routes (e.g. DELETE /v1/me) will respond 503.",
            )
        }
    }

    override suspend fun deleteUser(userId: UserId): DeleteUserResult {
        val key = config.serviceRoleKey.takeUnless { it.isNullOrBlank() }
            ?: return DeleteUserResult.NotConfigured

        return try {
            val response = client.delete("${config.projectUrl}/auth/v1/admin/users/${userId.value}") {
                header(HttpHeaders.Authorization, "Bearer $key")
                // Supabase Auth requires both Authorization AND the apikey
                // header on admin calls; they validate the apikey against
                // their project list before unpacking the bearer.
                header("apikey", key)
                // GoTrue soft-deletes by default, leaving the auth.users row
                // signable-in and never firing the ON DELETE CASCADE; the body
                // forces a hard delete so the credential and cascaded data go.
                contentType(ContentType.Application.Json)
                setBody(DeleteUserRequest(shouldSoftDelete = false))
            }
            when (response.status.value) {
                in 200..299 -> DeleteUserResult.Success
                404 -> DeleteUserResult.AlreadyGone
                else -> DeleteUserResult.Failure(response.status.value, null)
            }
        } catch (e: Throwable) {
            DeleteUserResult.Failure(statusCode = null, cause = e)
        }
    }

    override suspend fun updateUserDisplayName(
        userId: UserId,
        displayName: String,
    ): UpdateDisplayNameResult {
        val key = config.serviceRoleKey.takeUnless { it.isNullOrBlank() }
            ?: return UpdateDisplayNameResult.NotConfigured

        return try {
            val response = client.put("${config.projectUrl}/auth/v1/admin/users/${userId.value}") {
                header(HttpHeaders.Authorization, "Bearer $key")
                header("apikey", key)
                contentType(ContentType.Application.Json)
                // GoTrue merges user_metadata keys, so this only touches
                // display_name and leaves anything else (future keys) alone.
                setBody(UpdateUserMetadataRequest(UserMetadata(displayName = displayName)))
            }
            when (response.status.value) {
                in 200..299 -> UpdateDisplayNameResult.Success
                else -> UpdateDisplayNameResult.Failure(response.status.value, null)
            }
        } catch (e: Throwable) {
            UpdateDisplayNameResult.Failure(statusCode = null, cause = e)
        }
    }

    override suspend fun listAnonymousUsersOlderThan(olderThan: Instant): List<UserId> {
        val key = config.serviceRoleKey.takeUnless { it.isNullOrBlank() } ?: return emptyList()

        // Supabase's admin/users endpoint is page-based (max per_page=1000).
        // We page until we get an empty page or until safety cap kicks in
        // — a runaway loop here would hammer the upstream rate limiter.
        val matching = mutableListOf<UserId>()
        var page = 1
        val perPage = 1000
        val safetyCap = 100 // up to 100k users — V1 will never approach this.

        while (page <= safetyCap) {
            val response = try {
                client.get("${config.projectUrl}/auth/v1/admin/users") {
                    parameter("page", page)
                    parameter("per_page", perPage)
                    header(HttpHeaders.Authorization, "Bearer $key")
                    header("apikey", key)
                }
            } catch (e: Throwable) {
                LoggerFactory.getLogger(HttpSupabaseAdminClient::class.java).warn(
                    "Failed to page anon users at page=$page; sweep returning partial results", e,
                )
                return matching
            }
            if (response.status.value !in 200..299) {
                LoggerFactory.getLogger(HttpSupabaseAdminClient::class.java).warn(
                    "Admin /users page=$page returned ${response.status.value}; sweep returning partial results",
                )
                return matching
            }

            val body: AdminUsersListResponse = response.body()
            val users = body.users.orEmpty()
            if (users.isEmpty()) return matching

            for (user in users) {
                if (user.isAnonymous != true) continue
                val lastSeen = user.lastSignInAt?.let { parseRfc3339OrNull(it) }
                    ?: user.createdAt?.let { parseRfc3339OrNull(it) }
                    ?: continue
                if (lastSeen < olderThan) {
                    Catching { UserId.parse(user.id) }.getOrNull()?.let { matching += it }
                }
            }

            if (users.size < perPage) return matching
            page++
        }
        return matching
    }

    @Serializable
    private data class DeleteUserRequest(
        @SerialName("should_soft_delete") val shouldSoftDelete: Boolean,
    )

    @Serializable
    private data class UpdateUserMetadataRequest(
        @SerialName("user_metadata") val userMetadata: UserMetadata,
    )

    @Serializable
    private data class UserMetadata(
        @SerialName("display_name") val displayName: String,
    )

    @Serializable
    private data class AdminUsersListResponse(val users: List<AdminUser>? = null)

    @Serializable
    private data class AdminUser(
        val id: String,
        @SerialName("is_anonymous") val isAnonymous: Boolean? = null,
        @SerialName("last_sign_in_at") val lastSignInAt: String? = null,
        @SerialName("created_at") val createdAt: String? = null,
    )

    companion object {
        private val JSON_LENIENT = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        private fun parseRfc3339OrNull(value: String): Instant? = Catching {
            // Supabase emits ISO-8601 / RFC-3339 timestamps. java.time
            // parses them; we then bridge to kotlin.time.Instant via the
            // epoch-millis adapter that's already imported elsewhere.
            Instant.fromEpochMilliseconds(
                java.time.Instant.parse(value).toEpochMilli(),
            )
        }.getOrNull()
    }
}
