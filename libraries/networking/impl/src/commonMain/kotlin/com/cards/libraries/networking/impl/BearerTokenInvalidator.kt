package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.networking.AuthTokenInvalidator
import com.dangerfield.cards.libraries.networking.InternalNetworkingApi
import com.dangerfield.cards.libraries.networking.NetworkClient
import io.ktor.client.plugins.auth.authProvider
import io.ktor.client.plugins.auth.providers.BearerAuthProvider
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Clears the [BearerAuthProvider]'s cached token on the authenticated client so
 * the next request re-runs `loadTokens` against the current session. See
 * [AuthTokenInvalidator].
 *
 * Lives inside `:libraries:networking` and is a legitimate owner of the raw
 * client, hence the [InternalNetworkingApi] opt-in.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class BearerTokenInvalidator(
    private val networkClient: NetworkClient,
) : AuthTokenInvalidator {

    @OptIn(InternalNetworkingApi::class)
    override fun invalidate() {
        networkClient.authenticatedClient.authProvider<BearerAuthProvider>()?.clearToken()
    }
}
