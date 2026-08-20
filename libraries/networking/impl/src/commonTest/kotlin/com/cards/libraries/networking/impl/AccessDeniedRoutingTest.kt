package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.networking.AccessDeniedBus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.json.Json
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the network layer's reaction to a `403` carrying the locked
 * access-denied envelope: parse the wire fields and signal [AccessDeniedBus]
 * exactly once, so the app layer can route to the blocking screen. Equally
 * load-bearing: a 403 *without* the envelope (a route-level deny like
 * `not_host`) must NOT signal — the parser stays silent so existing failure
 * paths (e.g. `RoomRepositoryImpl` mapping 403 → `NotHost`) keep working.
 *
 * Built against a real Ktor [HttpClient] + [MockEngine] with the same
 * `expectSuccess` + `HttpResponseValidator` wiring as production, rather than
 * unit-testing [signalAccessDeniedIfEnveloped] in isolation — the load-bearing
 * property is the validator firing, not the helper alone.
 */
class AccessDeniedRoutingTest : CoroutineTest() {

    @Test
    fun forbiddenWithEnvelope_signalsBus_withWireFieldsVerbatim() = runTest {
        val bus = RecordingBus()
        val client = clientReturning(
            status = HttpStatusCode.Forbidden,
            body = """{"reason":"banned","until":"2026-12-01T00:00:00Z","appealUrl":"https://support.cards.app/appeal"}""",
            bus = bus,
        )

        assertFailsWith<ResponseException> {
            client.get("https://example.test/v1/me")
        }

        val denial = withTimeout(2.seconds) { bus.denials.first() }
        assertEquals("banned", denial.reason)
        assertEquals("2026-12-01T00:00:00Z", denial.until)
        assertEquals("https://support.cards.app/appeal", denial.appealUrl)
    }

    @Test
    fun forbiddenWithEnvelope_appealUrlNull_isAllowed() = runTest {
        val bus = RecordingBus()
        val client = clientReturning(
            status = HttpStatusCode.Forbidden,
            body = """{"reason":"suspended"}""",
            bus = bus,
        )

        assertFailsWith<ResponseException> {
            client.get("https://example.test/v1/anywhere")
        }

        val denial = withTimeout(2.seconds) { bus.denials.first() }
        assertEquals("suspended", denial.reason)
        assertEquals(null, denial.until)
        assertEquals(null, denial.appealUrl)
    }

    @Test
    fun forbiddenWithUnrelatedBody_doesNotSignal() = runTest {
        // A route-level 403 (e.g. RoomRoutes' `not_host`) returns a different
        // envelope shape — the parser must not deserialize it, and the caller's
        // existing failure path must remain unaffected.
        val bus = RecordingBus()
        val client = clientReturning(
            status = HttpStatusCode.Forbidden,
            body = """{"error":{"code":"not_host","message":"Only the host can add bots."}}""",
            bus = bus,
        )

        assertFailsWith<ResponseException> {
            client.get("https://example.test/v1/rooms/ABCD/bots")
        }

        assertTrue(bus.received.isEmpty(), "route-level 403 must not flow through the access-denied bus")
    }

    @Test
    fun nonForbiddenStatuses_neverSignal() = runTest {
        // 401 / 404 / 500 must not trigger the access-denied path even if the
        // body happens to be JSON — the gate is on status, not body shape.
        listOf(HttpStatusCode.Unauthorized, HttpStatusCode.NotFound, HttpStatusCode.InternalServerError)
            .forEach { status ->
                val bus = RecordingBus()
                val client = clientReturning(
                    status = status,
                    body = """{"reason":"banned"}""",
                    bus = bus,
                )

                assertFailsWith<ResponseException> {
                    client.get("https://example.test/v1/me")
                }

                assertTrue(bus.received.isEmpty(), "$status must not signal access-denied")
            }
    }

    private fun clientReturning(
        status: HttpStatusCode,
        body: String,
        bus: AccessDeniedBus,
    ): HttpClient {
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        return HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            HttpResponseValidator {
                handleResponseExceptionWithRequest { cause, _ ->
                    if (cause !is ResponseException) return@handleResponseExceptionWithRequest
                    if (cause.response.status == HttpStatusCode.Forbidden) {
                        signalAccessDeniedIfEnveloped(cause.response, bus, BannedStateImpl())
                    }
                }
            }
            expectSuccess = true
        }
    }

    private class RecordingBus : AccessDeniedBus {
        private val flow = MutableSharedFlow<AccessDeniedBus.Denial>(
            replay = 1,
            extraBufferCapacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val received = mutableListOf<AccessDeniedBus.Denial>()
        override val denials: Flow<AccessDeniedBus.Denial> = flow.asSharedFlow()
        override fun signalDenied(denial: AccessDeniedBus.Denial) {
            received += denial
            flow.tryEmit(denial)
        }
    }
}
