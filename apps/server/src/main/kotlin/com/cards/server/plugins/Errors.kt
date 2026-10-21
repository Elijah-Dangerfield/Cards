package com.dangerfield.cards.server.plugins

import com.dangerfield.cards.server.db.violatesUserIdForeignKey
import com.dangerfield.cards.server.domain.UnknownAuthUserException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * Every error response from this server has this shape. Defining it once means
 * the client only ever has one error envelope to deserialize and the server only
 * has one place to add fields (e.g. a trace ID later).
 *
 * Wire format:
 *
 * ```json
 * { "error": { "code": "unknown", "message": "…" } }
 * ```
 */
@Serializable
data class ProblemResponse(
    val error: Problem,
) {
    @Serializable
    data class Problem(
        val code: String,
        val message: String,
    )
}

private val logger = LoggerFactory.getLogger("StatusPages")

fun Application.installStatusPages() {
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ProblemResponse(ProblemResponse.Problem("bad_request", cause.message ?: "Bad request")),
            )
        }
        exception<UnknownAuthUserException> { call, cause ->
            call.respondAccountNotFound(cause.userId.toString(), detectedBy = "pre-flight")
        }
        exception<Throwable> { call, cause ->
            val callerId = call.userId()
            if (callerId != null && cause.violatesUserIdForeignKey()) {
                call.respondAccountNotFound(callerId.toString(), detectedBy = "fk-violation")
                return@exception
            }
            if (cause.isAbandonedSocketUpgrade()) {
                call.logAbandonedSocketUpgrade(callerId?.toString())
                return@exception
            }
            logger.error("Unhandled error", cause)
            captureToSentry(cause, context = "status-pages")
            call.respond(
                HttpStatusCode.InternalServerError,
                ProblemResponse(ProblemResponse.Problem("internal", cause.message ?: "unknown")),
            )
        }
    }
}

/**
 * A client that walked away during the WebSocket handshake (ENG-79 / CARDS-CM).
 *
 * Netty tears its `RequestBodyHandler` out of the pipeline when the channel
 * closes; Ktor's `respondUpgrade` then dereferences it and throws a plain NPE
 * from inside the engine, before our `webSocket { }` block ever runs. There is
 * nothing to fix in the handshake — the peer is gone — but it reached
 * [installStatusPages] as an unhandled 500, which both filed a Sentry issue per
 * occurrence and tried to write a response onto a dead connection.
 *
 * Matched on the frame rather than the message: the NPE is the JVM's generated
 * one, so its text is a Ktor implementation detail that a version bump can
 * reword, while the method it fails in is the thing we actually mean.
 */
internal fun Throwable.isAbandonedSocketUpgrade(): Boolean =
    this is NullPointerException &&
        stackTrace.any { it.methodName == "respondUpgrade" }

/**
 * Logged with the room and the user because the open question is whether this
 * is a client that closed the tab or a player who could not get into a table
 * (ENG-79); the path alone cannot tell those apart, and the Sentry issue it
 * used to file carried neither.
 */
private fun ApplicationCall.logAbandonedSocketUpgrade(userId: String?) {
    logger.warn(
        "Socket upgrade abandoned before the handshake completed: room={} user={} path={}",
        parameters["code"] ?: "unknown",
        userId ?: "anonymous",
        request.path(),
    )
}

/**
 * Answer a caller whose JWT verified but whose `auth.users` row is gone. `401`
 * rather than `409` so a client that doesn't recognise
 * [UnknownAuthUserException.WIRE_CODE] still does something sane — it
 * re-authenticates — instead of treating an unfamiliar status as a transient
 * failure and retrying forever.
 *
 * Logged at warn, not captured to Sentry: this is a client-state problem the
 * response already resolves, and the old `500` path was filing one Sentry issue
 * per doomed request.
 *
 * Only ever the answer when the *caller* is the missing user. A route that
 * writes some other user's row — `/v1/admin/grant-chips` takes its target id
 * from the operator — violates the same constraint without the caller's access
 * being in question, so the foreign-key net requires an authenticated user id
 * before it concludes anything about the session.
 */
private suspend fun ApplicationCall.respondAccountNotFound(userId: String, detectedBy: String) {
    logger.warn(
        "Authenticated caller {} has no auth.users row ({}) on {} {} — answering 401 {}",
        userId,
        detectedBy,
        request.httpMethod.value,
        request.path(),
        UnknownAuthUserException.WIRE_CODE,
    )
    respond(
        HttpStatusCode.Unauthorized,
        ProblemResponse(
            ProblemResponse.Problem(
                code = UnknownAuthUserException.WIRE_CODE,
                message = "This account no longer exists. Sign in again.",
            ),
        ),
    )
}
