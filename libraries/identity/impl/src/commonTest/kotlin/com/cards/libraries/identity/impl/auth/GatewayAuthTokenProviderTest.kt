package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Two-step contract:
 *  - [awaitReady] drives the bootstrap resolve.
 *  - [accessToken] is a synchronous peek of the gateway's session.
 *
 * Tests pin both halves and the refresh path.
 */
class GatewayAuthTokenProviderTest : CoroutineTest() {

    @Test
    fun accessToken_returnsGatewayToken_afterAwaitReady() = runUnitTest {
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.Authenticated,
            session = sampleSession(accessToken = "tok-abc"),
        )
        val provider = GatewayAuthTokenProvider(AuthBootstrap(gateway), gateway)

        provider.awaitReady()
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

        provider.awaitReady()
        // Bootstrap returned Failed → gateway has no session → token is null.
        assertNull(provider.accessToken())
    }

    @Test
    fun awaitReady_drivesAnonSignIn_thenAccessTokenSeesNewSession() = runUnitTest {
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.NotAuthenticated,
            session = null,
        )
        gateway.onSignInAnonymously = {
            advanceToAuthenticated(sampleSession(userId = "anon-1", accessToken = "tok-after-anon"))
        }
        val provider = GatewayAuthTokenProvider(AuthBootstrap(gateway), gateway)

        provider.awaitReady()
        assertEquals("tok-after-anon", provider.accessToken())
        assertEquals(1, gateway.signInAnonymouslyCalls)
    }

    @Test
    fun accessToken_withoutAwaitReady_doesNotDriveBootstrap() = runUnitTest {
        // Without awaitReady(), accessToken() is a pure peek. The gateway is
        // NotAuthenticated and accessToken returns null. The bearer plugin
        // is expected to see null and let the request go unauthed —
        // authedCall is responsible for the pre-flight awaitReady().
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.NotAuthenticated,
            session = null,
        )
        val provider = GatewayAuthTokenProvider(AuthBootstrap(gateway), gateway)

        assertNull(provider.accessToken())
        assertEquals(0, gateway.signInAnonymouslyCalls, "peek must not drive sign-in")
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
