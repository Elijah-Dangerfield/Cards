package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The token provider is a thin layer on top of [AuthBootstrap] + the
 * gateway: wait for resolve, then read the gateway's current session token.
 * Tests pin that contract and the refresh path.
 */
class GatewayAuthTokenProviderTest : CoroutineTest() {

    @Test
    fun accessToken_returnsGatewaySessionToken_afterBootstrapResolves() = runUnitTest {
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Authenticated,
            session = sampleSession(accessToken = "tok-abc"),
        )
        val provider = GatewayAuthTokenProvider(AuthBootstrap(gateway), gateway)

        assertEquals("tok-abc", provider.accessToken())
    }

    @Test
    fun accessToken_returnsNull_whenBootstrapFails() = runUnitTest {
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.NotAuthenticated,
            session = null,
        )
        gateway.onSignInAnonymously = { throw IllegalStateException("anon disabled") }
        val provider = GatewayAuthTokenProvider(AuthBootstrap(gateway), gateway)

        // Bootstrap returns Failed; gateway has no session; token reads null.
        assertNull(provider.accessToken())
    }

    @Test
    fun accessToken_waitsForAnonSignIn_beforeReading() = runUnitTest {
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.NotAuthenticated,
            session = null,
        )
        gateway.onSignInAnonymously = {
            advanceToAuthenticated(sampleSession(userId = "anon-1", accessToken = "tok-after-anon"))
        }
        val provider = GatewayAuthTokenProvider(AuthBootstrap(gateway), gateway)

        // First call drives the resolve loop; we'd race the gateway read
        // without the bootstrap await.
        assertEquals("tok-after-anon", provider.accessToken())
        assertEquals(1, gateway.signInAnonymouslyCalls)
    }

    @Test
    fun refreshAccessToken_callsGatewayRefresh_thenReturnsNewToken() = runUnitTest {
        val initialSession = sampleSession(accessToken = "tok-old")
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Authenticated,
            session = initialSession,
        )
        gateway.onRefreshSession = {
            replaceSession(initialSession.copy(accessToken = "tok-new"))
        }
        val provider = GatewayAuthTokenProvider(AuthBootstrap(gateway), gateway)

        assertEquals("tok-new", provider.refreshAccessToken())
        assertEquals(1, gateway.refreshSessionCalls)
    }

    @Test
    fun refreshAccessToken_returnsNull_whenGatewayThrows() = runUnitTest {
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Authenticated,
            session = sampleSession(accessToken = "tok-old"),
        )
        gateway.onRefreshSession = { throw IllegalStateException("network down") }
        val provider = GatewayAuthTokenProvider(AuthBootstrap(gateway), gateway)

        assertNull(provider.refreshAccessToken())
    }

    private fun sampleSession(
        userId: String = "user-1",
        accessToken: String = "tok-$userId",
        isAnonymous: Boolean = false,
        email: String? = "user@example.com",
    ): GatewaySession = GatewaySession(
        userId = userId,
        email = email,
        accessToken = accessToken,
        isAnonymous = isAnonymous,
        isEmailConfirmed = !isAnonymous,
    )
}
