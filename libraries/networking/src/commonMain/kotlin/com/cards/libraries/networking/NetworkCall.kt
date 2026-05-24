package com.dangerfield.cards.libraries.networking

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.shouldNotBeCaught
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException

private val networkCallLogger = KLog.withTag("NetworkCall")

/**
 * Authenticated HTTP call wrapper. Hands the authenticated [HttpClient] to
 * [block], wraps the result in [Catching], and emits a structured failure
 * log with a classification tag (timeout / http-status / exception-class)
 * so cross-repo failures share a consistent diagnostic shape.
 *
 * [description] is a short, stable identifier for the call — used as the
 * log message prefix. Pick something like `"inventory.sync"` or
 * `"wallet.fetch"` rather than a sentence; logs aggregate by description.
 *
 * Token-refresh on 401 still rides Ktor's `Auth` plugin (installed on
 * [NetworkClient.authenticatedClient]) — this helper does NOT do its own
 * retry. Callers that want backoff retry should compose with
 * `withBackoffRetry { authedCall(...) { ... } }`.
 *
 * Cooperative cancellation is preserved: cancellation exceptions are
 * re-thrown (via [shouldNotBeCaught]) rather than swallowed into the
 * Result, matching `Catching { }` semantics.
 */
suspend fun <T> NetworkClient.authedCall(
    description: String,
    block: suspend (HttpClient) -> T,
): Catching<T> {
    val outcome = try {
        Catching.success(block(authenticatedClient))
    } catch (t: Throwable) {
        if (t.shouldNotBeCaught) throw t
        Catching.failure(t)
    }
    return outcome.onFailure { throwable ->
        networkCallLogger.w(throwable) { "$description failed (${throwable.classifyForLog()})" }
    }
}

private fun Throwable.classifyForLog(): String = when (this) {
    is HttpRequestTimeoutException -> "timeout"
    is ResponseException -> "http ${response.status.value}"
    else -> this::class.simpleName ?: "unknown"
}
