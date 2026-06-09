package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Two-step contract:
 *  - [awaitReady] waits for supabase-kt to hydrate any persisted session. It
 *    does NOT create one (the app is session-less until onboarding).
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
        val provider = GatewayAuthTokenProvider(gateway)

        provider.awaitReady()
        assertEquals("tok-abc", provider.accessToken())
    }

    @Test
    fun accessToken_returnsNull_whenNoSession() = runUnitTest {
        // No persisted session and we don't create one — the peek is null and
        // the request goes unauthed (correct for onboarding's public calls).
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.NotAuthenticated,
            session = null,
        )
        val provider = GatewayAuthTokenProvider(gateway)

        provider.awaitReady()
        assertNull(provider.accessToken())
        assertEquals(0, gateway.signInAnonymouslyCalls, "awaitReady must never sign in")
    }

    @Test
    fun accessToken_withoutAwaitReady_isAPurePeek() = runUnitTest {
        val gateway = FakeSupabaseAuthGateway(
            initialStatus = AuthGatewayStatus.NotAuthenticated,
            session = null,
        )
        val provider = GatewayAuthTokenProvider(gateway)

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
        val provider = GatewayAuthTokenProvider(gateway)

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
        val provider = GatewayAuthTokenProvider(gateway)

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
