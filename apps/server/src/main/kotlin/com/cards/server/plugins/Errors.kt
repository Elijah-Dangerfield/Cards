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
            if (cause.violatesUserIdForeignKey()) {
                call.respondAccountNotFound(call.userId()?.toString(), detectedBy = "fk-violation")
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
 * Answer a caller whose JWT verified but whose `auth.users` row is gone. `401`
 * rather than `409` so a client that doesn't recognise
 * [UnknownAuthUserException.WIRE_CODE] still does something sane — it
 * re-authenticates — instead of treating an unfamiliar status as a transient
 * failure and retrying forever.
 *
 * Logged at warn, not captured to Sentry: this is a client-state problem the
 * response already resolves, and the old `500` path was filing one Sentry issue
 * per doomed request.
 */
private suspend fun ApplicationCall.respondAccountNotFound(userId: String?, detectedBy: String) {
    logger.warn(
        "Authenticated caller {} has no auth.users row ({}) on {} {} — answering 401 {}",
        userId ?: "unknown",
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
