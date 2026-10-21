package com.dangerfield.cards.server.plugins

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ENG-79 / CARDS-CM. A client that disconnects during the WebSocket handshake
 * makes Ktor's `respondUpgrade` dereference a `RequestBodyHandler` Netty has
 * already removed. The NPE escapes the engine into [installStatusPages], which
 * filed a Sentry issue per occurrence and tried to write a 500 onto a socket
 * that was already gone.
 *
 * Classified on the stack frame rather than the NPE's text — the text is the
 * JVM's generated helpful-NPE message naming a Ktor internal, which a version
 * bump can reword. So the frame is what these tests pin.
 */
class AbandonedSocketUpgradeTest {

    @Test
    fun theFailedHandshakeIsRecognised() {
        val cause = runCatching { respondUpgrade() }.exceptionOrNull()

        assertTrue(cause!!.isAbandonedSocketUpgrade())
    }

    @Test
    fun anyOtherNullPointerIsStillABug() {
        val cause = runCatching { dereferenceSomethingReal() }.exceptionOrNull()

        assertFalse(
            cause!!.isAbandonedSocketUpgrade(),
            "a real NPE must keep reaching Sentry",
        )
    }

    @Test
    fun anotherExceptionFromTheUpgradePathIsStillABug() {
        // The frame alone is not the test: only a null dereference in it is the
        // abandoned-peer signature. A genuine failure there still pages.
        val cause = runCatching { respondUpgradeButWorse() }.exceptionOrNull()

        assertFalse(cause!!.isAbandonedSocketUpgrade())
    }

    @Test
    fun ordinaryRoutesAreUntouched() = testApplication {
        application {
            installStatusPages()
            routing { get("/health") { call.respondText("ok") } }
        }

        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }
}

/**
 * Named for the Ktor method the real failure happens in, because the frame name
 * is the whole fixture.
 */
private fun respondUpgrade(): Nothing = throw NullPointerException(
    "Cannot invoke \"io.ktor.server.netty.cio.RequestBodyHandler.upgrade()\" " +
        "because \"bodyHandler\" is null",
)

private fun respondUpgradeButWorse(): Nothing = throw IllegalStateException("no engine")

private fun dereferenceSomethingReal(): Nothing = throw NullPointerException("a real bug")
