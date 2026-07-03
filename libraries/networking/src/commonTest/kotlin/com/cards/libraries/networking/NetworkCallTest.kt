package com.dangerfield.cards.libraries.networking

import com.dangerfield.cards.libraries.core.AuthReason
import com.dangerfield.cards.libraries.core.AuthRequirement
import com.dangerfield.cards.libraries.core.AuthUnready
import com.dangerfield.cards.libraries.core.AuthVerdict
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.get
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins the [authedCall] boundary mechanics: pre-flight short-circuit (a Blocked
 * verdict never invokes the block — no wire hit), and the post-flight remap of
 * a 401 into [AuthUnready] (SessionExpired) exactly when the rejection epoch
 * bumped mid-call. The verdict *table* is pinned in AuthGateImplTest; log
 * severity isn't asserted (KLog has no capture seam) — the rule lives in the
 * NetworkCall KDoc.
 */
class NetworkCallTest : CoroutineTest() {

    @Test
    fun blockedVerdict_shortCircuits_withoutInvokingTheBlock() = runUnitTest {
        val client = FakeNetworkClient(verdict = AuthVerdict.Blocked(AuthReason.Offline))
        var invoked = false

        val result = client.authedCall("test.call") { invoked = true }

        val failure = assertIs<AuthUnready>(result.exceptionOrNull())
        assertEquals(AuthReason.Offline, failure.reason)
        assertEquals(false, invoked, "a blocked call must never touch the wire")
    }

    @Test
    fun readyVerdict_invokesTheBlock_andReturnsItsValue() = runUnitTest {
        val client = FakeNetworkClient(verdict = AuthVerdict.Ready)

        val result = client.authedCall("test.call") { "payload" }

        assertEquals("payload", result.getOrNull())
    }

    @Test
    fun unauthorized_withRejectionEpochBump_remapsToSessionExpired() = runUnitTest {
        // The engine simulates the bearer plugin's refresh being rejected
        // mid-call: the bus epoch bumps, then the final 401 propagates.
        val client = FakeNetworkClient(verdict = AuthVerdict.Ready)
        client.respondWith = {
            client.epoch += 1
            respondError(HttpStatusCode.Unauthorized)
        }

        val result = client.authedCall("test.call") { http -> http.get("/x") }

        val failure = assertIs<AuthUnready>(result.exceptionOrNull())
        assertEquals(AuthReason.SessionExpired, failure.reason)
        assertIs<ClientRequestException>(failure.cause, "the original 401 rides along as the cause")
    }

    @Test
    fun unauthorized_withoutRejection_staysARawHttpFailure() = runUnitTest {
        // A 401 whose refresh failed transiently (no epoch bump) is a
        // connectivity story — it must NOT masquerade as session death.
        val client = FakeNetworkClient(verdict = AuthVerdict.Ready)
        client.respondWith = { respondError(HttpStatusCode.Unauthorized) }

        val result = client.authedCall("test.call") { http -> http.get("/x") }

        assertIs<ClientRequestException>(result.exceptionOrNull())
    }

    @Test
    fun nonUnauthorizedFailures_passThroughUntouched_evenWithAnEpochBump() = runUnitTest {
        val client = FakeNetworkClient(verdict = AuthVerdict.Ready)
        client.respondWith = {
            client.epoch += 1
            respondError(HttpStatusCode.InternalServerError)
        }

        val result = client.authedCall("test.call") { http -> http.get("/x") }

        assertIs<ServerResponseException>(result.exceptionOrNull())
    }

    @Test
    fun authedCall_passesItsRequirementToTheVerdict() = runUnitTest {
        val client = FakeNetworkClient(verdict = AuthVerdict.Ready)

        client.authedCall("test.call", requirement = AuthRequirement.ClaimedAccount) { }

        assertEquals(AuthRequirement.ClaimedAccount, client.lastRequirement)
    }

    @Test
    fun blockedVerdict_shortCircuitsTheWebSocketUpgrade() = runUnitTest {
        val client = FakeNetworkClient(verdict = AuthVerdict.Blocked(AuthReason.NeedAccount))

        val result = client.authedWebSocketSession("test.socket") { }

        val failure = assertIs<AuthUnready>(result.exceptionOrNull())
        assertEquals(AuthReason.NeedAccount, failure.reason)
        assertTrue(client.requestCount == 0, "a blocked upgrade must never touch the wire")
    }

    @OptIn(InternalNetworkingApi::class)
    private class FakeNetworkClient(
        private val verdict: AuthVerdict,
    ) : NetworkClient {
        var epoch = 0L
        var lastRequirement: AuthRequirement? = null
        var respondWith: MockRequestHandleScope.() -> HttpResponseData = { respond("ok") }
        var requestCount = 0
            private set

        private val engine = MockEngine { _ ->
            requestCount++
            respondWith()
        }

        override val client: HttpClient by lazy { HttpClient(engine) { expectSuccess = true } }
        override val authenticatedClient: HttpClient by lazy { HttpClient(engine) { expectSuccess = true } }

        override suspend fun awaitAuthReady() = Unit
        override suspend fun authVerdict(requirement: AuthRequirement): AuthVerdict {
            lastRequirement = requirement
            return verdict
        }
        override val sessionRejectionEpoch: Long get() = epoch
    }
}
