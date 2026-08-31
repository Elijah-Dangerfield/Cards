package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * AUTH-29. The server answers a verified token whose `auth.users` row is gone
 * with `401 account_not_found`. Supabase itself still honours that token, so the
 * bearer plugin's refresh never fails and nothing else in the client would ever
 * notice — every sync would keep re-firing writes that can't succeed. The
 * validator has to spot the code and hand the session to the rejection path.
 *
 * The other half matters just as much: an ordinary 401 (expired token, no
 * bearer, a 401 from something that isn't us) must NOT sign the user out.
 */
class AccountMissingRoutingTest : CoroutineTest() {

    @Test
    fun unauthorizedWithAccountNotFound_signalsOnce() = runTest {
        var signals = 0
        val client = clientReturning(
            status = HttpStatusCode.Unauthorized,
            body = """{"error":{"code":"account_not_found","message":"This account no longer exists."}}""",
            onAccountMissing = { signals++ },
        )

        assertFailsWith<ResponseException> { client.get("https://example.test/v1/me/wallet/sync") }

        assertEquals(1, signals)
    }

    @Test
    fun ordinaryUnauthorized_doesNotSignal() = runTest {
        var signals = 0
        val client = clientReturning(
            status = HttpStatusCode.Unauthorized,
            body = """{"error":{"code":"unauthorized","message":"Missing or invalid access token"}}""",
            onAccountMissing = { signals++ },
        )

        assertFailsWith<ResponseException> { client.get("https://example.test/v1/me") }

        assertEquals(0, signals, "a plain expired token is recoverable — refreshing it is the right answer")
    }

    @Test
    fun unauthorizedWithAnUnparseableBody_doesNotSignal() = runTest {
        // An upstream proxy or a captive portal can 401 with HTML. Tearing the
        // session down over that would sign people out on hotel wifi.
        var signals = 0
        val client = clientReturning(
            status = HttpStatusCode.Unauthorized,
            body = "<html><body>Sign in to the network</body></html>",
            onAccountMissing = { signals++ },
        )

        assertFailsWith<ResponseException> { client.get("https://example.test/v1/me") }

        assertEquals(0, signals)
    }

    @Test
    fun theSameCodeOnAnotherStatus_doesNotSignal() = runTest {
        var signals = 0
        val client = clientReturning(
            status = HttpStatusCode.InternalServerError,
            body = """{"error":{"code":"account_not_found","message":"whoops"}}""",
            onAccountMissing = { signals++ },
        )

        assertFailsWith<ResponseException> { client.get("https://example.test/v1/me") }

        assertEquals(0, signals, "the gate is status plus code, so a 5xx can never end a session")
    }

    private fun clientReturning(
        status: HttpStatusCode,
        body: String,
        onAccountMissing: suspend () -> Unit,
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
                    if (cause.response.status == HttpStatusCode.Unauthorized) {
                        signalAccountMissingIfEnveloped(cause.response, onAccountMissing)
                    }
                }
            }
            expectSuccess = true
        }
    }
}
