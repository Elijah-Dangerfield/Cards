package com.dangerfield.cards.libraries.networking

import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins which HTTP refusals are allowed to skip the error tier. The set is
 * deliberately short, and the test exists so widening it is a decision
 * someone makes on purpose.
 */
class ExpectedClientErrorsTest : CoroutineTest() {

    @Test
    fun refusalsTheClientAlreadyActsOn_areNotErrors() = runUnitTest {
        for (status in listOf(
            HttpStatusCode.Unauthorized,
            HttpStatusCode.Forbidden,
            HttpStatusCode.TooManyRequests,
        )) {
            assertTrue(
                responseException(status).isExpectedClientError(),
                "$status is the server answering correctly",
            )
        }
    }

    @Test
    fun malformedRequestsAndServerFaults_stayErrors() = runUnitTest {
        for (status in listOf(
            HttpStatusCode.BadRequest,
            HttpStatusCode.Conflict,
            HttpStatusCode.NotFound,
            HttpStatusCode.InternalServerError,
        )) {
            assertFalse(
                responseException(status).isExpectedClientError(),
                "$status means something is actually wrong",
            )
        }
    }

    @Test
    fun aRateLimit_reachesTheTelemetrySinksAsExpected() = runUnitTest {
        // Every sink consults isExpectedFailure() and only that, so this is
        // the assertion that actually keeps a 429 out of Sentry.
        assertTrue(responseException(HttpStatusCode.TooManyRequests).isExpectedFailure())
    }

    @Test
    fun onlyTooManyRequests_readsAsRateLimited() = runUnitTest {
        assertTrue(responseException(HttpStatusCode.TooManyRequests).isRateLimited())
        assertFalse(responseException(HttpStatusCode.Forbidden).isRateLimited())
        assertFalse(RuntimeException("socket closed").isRateLimited())
    }

    @Test
    fun aWrappedRateLimit_isStillARateLimit() = runUnitTest {
        // Syncers wrap API failures on the way back up; the status has to
        // survive the cause chain or the predicate is useless in practice.
        val wrapped = IllegalStateException(
            "equipment sync failed",
            responseException(HttpStatusCode.TooManyRequests),
        )

        assertTrue(wrapped.isRateLimited())
        assertTrue(wrapped.isExpectedClientError())
    }

    private suspend fun responseException(status: HttpStatusCode): ResponseException {
        val mock = HttpClient(MockEngine { respond(content = ByteReadChannel("{}"), status = status) }) {
            expectSuccess = true
        }
        return try {
            mock.get("/")
            error("expected a ResponseException for $status")
        } catch (e: ResponseException) {
            e
        } finally {
            mock.close()
        }
    }
}
