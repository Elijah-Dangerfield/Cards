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
 * Supplies the access token to the network layer. Waits for [AuthBootstrap] to
 * resolve (so first-launch anon sign-in completes before the first authed
 * request fires), then reads the current session token from the gateway.
 *
 * Deliberately narrow surface — no [AuthRepository] dep — so the construction
 * graph stays linear: `NetworkClient → AuthTokenProvider → AuthBootstrap →
 * SupabaseAuthGateway`. The cycle the codebase used to dodge with a lazy
 * provider is gone at the type level.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AuthTokenProvider::class)
@Inject
class GatewayAuthTokenProvider(
    private val authBootstrap: AuthBootstrap,
    private val gateway: SupabaseAuthGateway,
) : AuthTokenProvider {

    private val logger = KLog.withTag("AuthTokenProvider")

    override suspend fun accessToken(): String? {
        // Wait for the bootstrap to settle. After this, the gateway either
        // holds a real session or we know it can't. Either way, the read
        // below is the truth.
        authBootstrap.awaitResolved()
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
