package com.dangerfield.cards.libraries.cards.impl.logging

import com.dangerfield.cards.libraries.core.AuthReason
import com.dangerfield.cards.libraries.core.AuthUnready
import com.dangerfield.cards.libraries.core.logging.LogContext
import com.dangerfield.cards.libraries.core.logging.LogEntry
import com.dangerfield.cards.libraries.core.logging.LogLevel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SentryLogTreeTest {

    private val tree = SentryLogTree(
        minBreadcrumbLevel = LogLevel.Info,
        minEventLevel = LogLevel.Error,
    )

    @Test
    fun `expected control-flow throwable at error level is not captured as an event`() {
        assertFalse(tree.shouldCaptureEvent(errorEntry(AuthUnready(AuthReason.FinishingSetup))))
        assertFalse(tree.shouldCaptureEvent(errorEntry(AuthUnready(AuthReason.NeedAccount))))
    }

    @Test
    fun `real throwable at error level is captured as an event`() {
        assertTrue(tree.shouldCaptureEvent(errorEntry(IllegalStateException("boom"))))
    }

    @Test
    fun `error-level message without a throwable is captured as an event`() {
        assertTrue(tree.shouldCaptureEvent(errorEntry(throwable = null)))
    }

    @Test
    fun `a server refusing the caller is not captured as an event`() = runTest {
        // ENG-35: one banned account 403-ing on every sync filled the error
        // panel (Sentry CARDS-BG) with a condition nobody could act on — the
        // server was working and the user was already looking at the block.
        assertFalse(tree.shouldCaptureEvent(errorEntry(refusalFrom(HttpStatusCode.Forbidden))))
        assertFalse(tree.shouldCaptureEvent(errorEntry(refusalFrom(HttpStatusCode.Unauthorized))))
    }

    @Test
    fun `a refusal wrapped by a caller is still not captured`() = runTest {
        val wrapped = IllegalStateException("sync failed", refusalFrom(HttpStatusCode.Forbidden))

        assertFalse(tree.shouldCaptureEvent(errorEntry(wrapped)))
    }

    @Test
    fun `other client errors are still captured`() = runTest {
        // A 400 or a 409 means we sent something the server didn't expect. That
        // is a bug, and dropping it would hide real breakage behind this fix.
        assertTrue(tree.shouldCaptureEvent(errorEntry(refusalFrom(HttpStatusCode.BadRequest))))
        assertTrue(tree.shouldCaptureEvent(errorEntry(refusalFrom(HttpStatusCode.Conflict))))
        assertTrue(tree.shouldCaptureEvent(errorEntry(refusalFrom(HttpStatusCode.InternalServerError))))
    }

    @Test
    fun `below-threshold entries are never captured as events`() {
        assertFalse(
            tree.shouldCaptureEvent(
                LogEntry(
                    level = LogLevel.Warn,
                    tag = "test",
                    message = "warn",
                    throwable = IllegalStateException("boom"),
                    context = LogContext.Empty,
                )
            )
        )
    }

    /**
     * A genuine [ResponseException] carrying [status], produced the way the app
     * produces one: a real client failing a real (mocked) round-trip. Building
     * the exception by hand would need an `HttpResponse`, and a hand-made one
     * proves less about what actually reaches the log.
     */
    private suspend fun refusalFrom(status: HttpStatusCode): ResponseException {
        val client = HttpClient(MockEngine { respond(content = "", status = status) }) {
            expectSuccess = true
        }
        return assertFailsWith<ResponseException> { client.get("https://example.test/v1/equipment/sync") }
    }

    private fun errorEntry(throwable: Throwable?): LogEntry = LogEntry(
        level = LogLevel.Error,
        tag = "test",
        message = throwable?.message ?: "error",
        throwable = throwable,
        context = LogContext.Empty,
    )
}
