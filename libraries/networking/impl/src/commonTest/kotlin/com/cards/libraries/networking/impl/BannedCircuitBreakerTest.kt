package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.networking.AccessDeniedBus
import com.dangerfield.cards.libraries.networking.indicatesBackendUnreachable
import com.dangerfield.cards.libraries.networking.isExpectedFailure
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ENG-35. A banned account's client kept sending ordinary requests — each one a
 * full round-trip to be told `403 {"reason":"banned"}` again, each one a Sentry
 * error (CARDS-BG), none of them able to change anything, because the user was
 * looking at the blocking screen the whole time.
 *
 * What has to hold: while banned, a normal request never reaches the wire, and
 * the recovery path still does. Built against a real client + [MockEngine] so
 * "never reached the wire" is measured by the engine's own request count rather
 * than trusted from the plugin's point of view.
 */
class BannedCircuitBreakerTest : CoroutineTest() {

    private val banned = AccessDeniedBus.Denial(reason = "banned", until = null, appealUrl = null)

    @Test
    fun whileBanned_anOrdinaryRequestNeverReachesTheWire() = runTest {
        val state = BannedStateImpl().apply { setBanned(banned) }
        val engine = engineReturning(HttpStatusCode.OK, "{}")
        val client = clientWith(engine, state)

        assertFailsWith<BannedShortCircuit> { client.post("https://example.test/v1/equipment/sync") }

        assertEquals(0, engine.requestHistory.size, "the whole point is that nothing goes out")
    }

    @Test
    fun theShortCircuitIsExpectedControlFlow_soTelemetryDropsIt() = runTest {
        val state = BannedStateImpl().apply { setBanned(banned) }
        val client = clientWith(engineReturning(HttpStatusCode.OK, "{}"), state)

        val thrown = assertFailsWith<BannedShortCircuit> {
            client.post("https://example.test/v1/wallet/sync")
        }

        // Asserting `isExpectedControlFlow` here would only restate the class
        // declaration. What the name claims is that the sinks drop it, and the
        // sinks read `isExpectedFailure()`.
        assertTrue(thrown.isExpectedFailure(), "a working breaker must not read as an app failure")
    }

    @Test
    fun whileBanned_theStatusProbeStillGoesOut() = runTest {
        val state = BannedStateImpl().apply { setBanned(banned) }
        val engine = engineReturning(HttpStatusCode.OK, """{"userId":"u"}""")
        val client = clientWith(engine, state)

        client.get("https://example.test/v1/me")

        assertEquals(1, engine.requestHistory.size, "blocking the probe would make the ban permanent")
    }

    @Test
    fun whileBanned_appConfigStillGoesOut() = runTest {
        // Remote config carries the kill switches. A blocked client we can't
        // steer is worse than a blocked client that makes one extra request.
        val state = BannedStateImpl().apply { setBanned(banned) }
        val engine = engineReturning(HttpStatusCode.OK, "{}")
        val client = clientWith(engine, state)

        client.get("https://example.test/v1/app-config")

        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun theSyncWritesUnderTheProbePathAreStillBlocked() = runTest {
        // The allowlist matches /v1/me exactly. A prefix match would let every
        // /v1/me/*/sync write straight through, which is most of the traffic
        // this exists to stop.
        val state = BannedStateImpl().apply { setBanned(banned) }
        val engine = engineReturning(HttpStatusCode.OK, "{}")
        val client = clientWith(engine, state)

        assertFailsWith<BannedShortCircuit> { client.post("https://example.test/v1/me/achievements/sync") }

        assertEquals(0, engine.requestHistory.size)
    }

    @Test
    fun whenNotBanned_everythingGoesOut() = runTest {
        val engine = engineReturning(HttpStatusCode.OK, "{}")
        val client = clientWith(engine, BannedStateImpl())

        client.post("https://example.test/v1/equipment/sync")

        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun aSuccessfulStatusProbeLiftsTheBan_andTrafficResumes() = runTest {
        val state = BannedStateImpl().apply { setBanned(banned) }
        val engine = engineReturning(HttpStatusCode.OK, """{"userId":"u"}""")
        val client = clientWith(engine, state)

        client.get("https://example.test/v1/me")

        assertEquals(null, state.denial.value, "a clean probe is what recovery means")
        client.post("https://example.test/v1/equipment/sync")
        assertEquals(2, engine.requestHistory.size, "and the app works again without a relaunch")
    }

    @Test
    fun anUnrelatedSuccessDoesNotLiftTheBan() = runTest {
        // Only the probe path is evidence. Clearing on any 200 would let a
        // public app-config fetch unblock a client the server still refuses.
        val state = BannedStateImpl().apply { setBanned(banned) }
        val client = clientWith(engineReturning(HttpStatusCode.OK, "{}"), state)

        client.get("https://example.test/v1/app-config")

        assertEquals(banned, state.denial.value)
    }

    @Test
    fun aDeniedProbeReLatchesTheBan() = runTest {
        val state = BannedStateImpl()
        val engine = engineReturning(
            HttpStatusCode.Forbidden,
            """{"reason":"banned","until":"2026-12-01T00:00:00Z","appealUrl":"https://appeal.test"}""",
        )
        val client = clientWith(engine, state)

        assertFailsWith<ResponseException> { client.get("https://example.test/v1/me") }

        assertEquals("banned", state.denial.value?.reason)
        assertEquals("https://appeal.test", state.denial.value?.appealUrl)
    }

    @Test
    fun aBlockedRequestIsNotEvidenceTheBackendIsDown() = runTest {
        // The breaker throws before anything hits the wire, so the failure has
        // no response attached and looks exactly like a timeout to the
        // reachability validator. Counting it would flip a banned user to
        // "offline" after two blocked calls — and because every non-allowlisted
        // call is now short-circuited, nothing would ever report reachable
        // again to reset the run. The offline banner would stick for the
        // session on top of the blocking screen.
        val state = BannedStateImpl().apply { setBanned(banned) }
        val reachability = RecordingReachability()
        val client = clientWith(engineReturning(HttpStatusCode.OK, "{}"), state, reachability)

        assertFailsWith<BannedShortCircuit> { client.post("https://example.test/v1/equipment/sync") }
        assertFailsWith<BannedShortCircuit> { client.post("https://example.test/v1/wallet/sync") }

        assertEquals(
            0,
            reachability.unreachableReports,
            "a request we refused to send says nothing about whether the server is up",
        )
        assertTrue(
            IllegalStateException("connect timed out").indicatesBackendUnreachable(),
            "a genuine empty-handed failure must still count, or the banner never fires",
        )
    }

    private class RecordingReachability {
        var unreachableReports = 0
            private set

        fun reportUnreachable() { unreachableReports++ }
    }

    private fun engineReturning(status: HttpStatusCode, body: String) = MockEngine { _ ->
        respond(
            content = ByteReadChannel(body),
            status = status,
            headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
        )
    }

    private fun clientWith(
        engine: MockEngine,
        state: BannedStateImpl,
        reachability: RecordingReachability = RecordingReachability(),
    ): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(bannedCircuitBreaker(state))
            HttpResponseValidator {
                validateResponse { response -> clearBanIfStatusProbeSucceeded(response, state) }
                handleResponseExceptionWithRequest { cause, _ ->
                    // Mirrors the production validator in NetworkClientImpl. The
                    // shape matters: it is the "no response ⇒ unreachable"
                    // branch that misread a refusal to send as an outage.
                    if (cause !is ResponseException) {
                        if (cause.indicatesBackendUnreachable()) reachability.reportUnreachable()
                        return@handleResponseExceptionWithRequest
                    }
                    if (cause.response.status == HttpStatusCode.Forbidden) {
                        signalAccessDeniedIfEnveloped(cause.response, NoopAccessDeniedBus, state)
                    }
                }
            }
            expectSuccess = true
        }

    private object NoopAccessDeniedBus : AccessDeniedBus {
        override val denials = kotlinx.coroutines.flow.emptyFlow<AccessDeniedBus.Denial>()
        override fun signalDenied(denial: AccessDeniedBus.Denial) = Unit
    }
}
