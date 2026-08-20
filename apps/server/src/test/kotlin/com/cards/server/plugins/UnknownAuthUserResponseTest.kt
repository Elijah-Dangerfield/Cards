package com.dangerfield.cards.server.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.server.domain.UnknownAuthUserException
import com.dangerfield.cards.server.domain.UserId
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import org.postgresql.util.PSQLException
import org.postgresql.util.PSQLState
import org.postgresql.util.ServerErrorMessage
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AUTH-29. A valid JWT naming a user id that isn't in `auth.users` used to
 * reach the generic `exception<Throwable>` handler, so the client got a raw
 * `500` whose body quoted the `*_user_id_fk` constraint, and every retry filed
 * a fresh Sentry issue.
 *
 * Both detectors have to land on the same wire answer: the explicit pre-flight
 * in the profile-create path, and the foreign-key net behind every other
 * per-user write.
 */
class UnknownAuthUserResponseTest {

    private val testIssuer = "https://test-project.supabase.co/auth/v1"
    private val testSecret = "0123456789abcdef0123456789abcdef0123456789abcdef"
    private val userId = UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"))

    @Test
    fun preFlightDetection_answers401WithTheAccountNotFoundCode() = runTest {
        callFailing(UnknownAuthUserException(userId)) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            val body = resp.bodyAsText()
            assertTrue(
                body.contains(UnknownAuthUserException.WIRE_CODE),
                "the client routes on the code, so it has to be on the wire: $body",
            )
        }
    }

    @Test
    fun foreignKeyViolation_answers401_soAWriteThatSkippedThePreFlightStillReadsAsReAuth() = runTest {
        callFailing(wrappedFkViolation(constraint = "wallets_user_id_fk")) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertTrue(resp.bodyAsText().contains(UnknownAuthUserException.WIRE_CODE))
        }
    }

    @Test
    fun foreignKeyViolation_isRecognisedFromTheMessage_whenTheDriverParsedNoConstraintField() = runTest {
        val bare = PSQLException(
            "ERROR: insert or update on table violates foreign key constraint inventory_user_id_fk",
            PSQLState.FOREIGN_KEY_VIOLATION,
        )
        callFailing(RuntimeException("exposed wrapper", bare)) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertTrue(resp.bodyAsText().contains(UnknownAuthUserException.WIRE_CODE))
        }
    }

    @Test
    fun theResponseLeaksNoSchemaDetail() = runTest {
        callFailing(wrappedFkViolation(constraint = "wallets_user_id_fk")) { resp ->
            val body = resp.bodyAsText()
            assertFalse(body.contains("user_id_fk"), "constraint name leaked: $body")
            assertFalse(body.contains("foreign key"), "schema detail leaked: $body")
            assertFalse(body.contains("wallets"), "table name leaked: $body")
        }
    }

    @Test
    fun anUnrelatedForeignKeyViolationStillReadsAs500() = runTest {
        // room_members.room_code is the other foreign key in the schema. Joining
        // a room that vanished is not an auth problem and must not tell the
        // client to sign out.
        callFailing(wrappedFkViolation(constraint = "room_members_room_code_fkey")) { resp ->
            assertEquals(HttpStatusCode.InternalServerError, resp.status)
        }
    }

    @Test
    fun anOrdinaryFailureIsUntouched() = runTest {
        callFailing(IllegalStateException("something else broke")) { resp ->
            assertEquals(HttpStatusCode.InternalServerError, resp.status)
            assertTrue(resp.bodyAsText().contains("internal"))
        }
    }

    @Test
    fun aHealthyRequestIsUnaffected() = runTest {
        callFailing(failure = null) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
        }
    }

    /**
     * A foreign-key violation shaped the way the driver hands one to Exposed: a
     * [PSQLException] carrying a parsed [ServerErrorMessage], wrapped by the
     * layer above. [ServerErrorMessage] parses NUL-delimited `<field-char><value>`
     * segments; `C` is the SQLSTATE and `n` the constraint name.
     */
    private fun wrappedFkViolation(constraint: String): Throwable {
        val nul = Char(0).toString()
        val fields = listOf("SERROR", "C23503", "Mviolates a constraint", "n$constraint")
        val serverError = ServerErrorMessage(fields.joinToString(nul) + nul)
        return RuntimeException("exposed wrapper", PSQLException(serverError))
    }

    private suspend fun callFailing(failure: Throwable?, assert: suspend (HttpResponse) -> Unit) {
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing {
                    authenticate(SUPABASE_JWT_AUTH) {
                        get(PROBE_PATH) {
                            if (failure != null) throw failure
                            call.respondText("ok")
                        }
                    }
                }
            }
            assert(
                client.get(PROBE_PATH) {
                    header(HttpHeaders.Authorization, "Bearer ${validJwt()}")
                },
            )
        }
    }

    private fun validJwt(): String = JWT.create()
        .withIssuer(testIssuer)
        .withAudience("authenticated")
        .withSubject(userId.value.toString())
        .withIssuedAt(Date())
        .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
        .sign(Algorithm.HMAC256(testSecret))

    private val testVerifier = JWT.require(Algorithm.HMAC256(testSecret))
        .withIssuer(testIssuer)
        .withAudience("authenticated")
        .build()

    private companion object {
        const val PROBE_PATH = "/v1/me/probe"
    }
}
