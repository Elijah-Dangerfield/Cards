package com.dangerfield.cards.libraries.networking

import com.dangerfield.cards.libraries.core.Catching
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import kotlinx.serialization.Serializable

/**
 * The server's machine-readable error envelope: `{"error":{"code","message"}}`,
 * carried on 4xx responses whose status alone isn't specific enough (e.g. a 400
 * that's `insufficient_balance` vs `invalid_range`). Repos branch on [code] and
 * surface [message]; both are optional because not every 4xx is enveloped.
 */
@Serializable
data class ApiProblem(
    val code: String? = null,
    val message: String? = null,
)

/**
 * Decodes the [ApiProblem] envelope off a failed response, or null when the
 * body isn't enveloped (a proxy 4xx, HTML error page, empty body). Safe to call
 * from a `catch` — Ktor's [ResponseException] holds a saved copy of the
 * response, so reading the body here can't race the caller's own decoding.
 */
suspend fun ResponseException.apiProblemOrNull(): ApiProblem? =
    Catching { response.body<ProblemEnvelope>().error }.getOrNull()

suspend fun ResponseException.apiErrorCode(): String? = apiProblemOrNull()?.code

suspend fun ResponseException.apiErrorMessage(): String? = apiProblemOrNull()?.message

@Serializable
private data class ProblemEnvelope(val error: ApiProblem)
