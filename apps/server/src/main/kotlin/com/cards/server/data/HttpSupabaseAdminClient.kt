package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.config.SupabaseConfig
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.DeleteUserResult
import com.dangerfield.cards.server.domain.SupabaseAdminClient
import com.dangerfield.cards.server.domain.UserId
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import me.tatarka.inject.annotations.Inject
import org.slf4j.LoggerFactory
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

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
        if (engine != null) HttpClient(engine) else HttpClient(CIO)
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
}
