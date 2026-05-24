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
 *  - [awaitReady] drives [AuthBootstrap.awaitResolved] to completion.
 *    [NetworkClient.awaitAuthReady] calls this before issuing a request,
 *    so the request timeout clock doesn't include the bootstrap wait.
 *  - [accessToken] is a synchronous peek of the gateway's current session.
 *    Returns null if there's no session. Ktor's bearer plugin calls this
 *    inside `loadTokens` — by then the bootstrap is resolved and the
 *    peek is instant.
 *
 * Deliberately narrow — no [AuthRepository] dep — so the construction
 * graph stays linear: `NetworkClient → AuthTokenProvider → AuthBootstrap
 * → SupabaseAuthGateway`.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AuthTokenProvider::class)
@Inject
class GatewayAuthTokenProvider(
    private val authBootstrap: AuthBootstrap,
    private val gateway: SupabaseAuthGateway,
) : AuthTokenProvider {

    private val logger = KLog.withTag("AuthTokenProvider")

    override suspend fun awaitReady() {
        authBootstrap.awaitResolved()
    }

    override suspend fun accessToken(): String? {
        val token = gateway.currentSession()?.accessToken
        if (token == null) {
            logger.w { "accessToken: no session — request will go unauthed" }
        }
        return token
    }

    override suspend fun refreshAccessToken(): String? {
        logger.d { "refreshAccessToken: forcing gateway session refresh" }
        return Catching {
            gateway.refreshSession()
            gateway.currentSession()?.accessToken
        }.logOnFailure { "Force refresh of access token failed" }.getOrNull()
    }
}
