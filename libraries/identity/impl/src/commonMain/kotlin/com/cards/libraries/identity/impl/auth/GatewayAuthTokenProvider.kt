package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.networking.AuthTokenProvider
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Supplies the access token to the network layer. Two-step contract:
 *
 *  - [awaitReady] suspends until supabase-kt has hydrated any persisted
 *    session. [NetworkClient.awaitAuthReady] calls this before issuing a
 *    request, so the request timeout clock doesn't include the hydration wait.
 *    It does **not** create a session — the app is intentionally session-less
 *    until onboarding explicitly creates an account
 *    ([AuthRepository.createGuestSession] or a sign-in). A pre-account request
 *    therefore goes unauthed, which is correct: onboarding only hits public
 *    endpoints.
 *  - [accessToken] is a synchronous peek of the gateway's current session.
 *    Returns null if there's no session. Ktor's bearer plugin calls this
 *    inside `loadTokens`.
 *
 * Deliberately narrow — depends only on [SupabaseAuthGateway], no
 * [AuthRepository] — so the construction graph stays linear:
 * `NetworkClient → AuthTokenProvider → SupabaseAuthGateway`.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AuthTokenProvider::class)
@Inject
class GatewayAuthTokenProvider(
    private val gateway: SupabaseAuthGateway,
) : AuthTokenProvider {

    private val logger = KLog.withTag("AuthTokenProvider")

    override suspend fun awaitReady() {
        gateway.awaitInitialization()
    }

    override suspend fun accessToken(): String? {
        val token = gateway.currentSession()?.accessToken
        if (token == null) {
            logger.w { "accessToken: no session — request will go unauthed" }
        }
        return token
    }

    override suspend fun refreshAccessToken(): String? {
        // No session at all — e.g. a fresh install before onboarding creates an
        // account, where a public request 401s and Ktor's bearer plugin tries a
        // refresh anyway. There's nothing to refresh; calling the gateway would
        // throw "No refresh token found in current session". Skip it and let the
        // request proceed unauthed (the documented null-means-unauthed contract).
        if (gateway.currentSession() == null) {
            logger.d { "refreshAccessToken: no session — nothing to refresh, going unauthed" }
            return null
        }
        logger.d { "refreshAccessToken: forcing gateway session refresh" }
        return Catching {
            gateway.refreshSession()
            gateway.currentSession()?.accessToken
        }.logOnFailure { "Force refresh of access token failed" }.getOrNull()
    }
}
