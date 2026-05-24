package com.dangerfield.cards.libraries.networking

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.networking.retry.RetryPolicy
import com.dangerfield.cards.libraries.networking.retry.withRetry
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException

private val networkCallLogger = KLog.withTag("NetworkCall")

/**
 * Authenticated HTTP call wrapper. Hands the authenticated [HttpClient] to
 * [block], wraps each attempt in [Catching], and emits a structured failure
 * log with a classification tag (timeout / http-status / exception-class)
 * so cross-repo failures share a consistent diagnostic shape.
 *
 * [description] is a short, stable identifier for the call — used as the log
 * message prefix. Pick `"inventory.sync"` or `"wallet.fetch"`, not a sentence;
 * logs aggregate by description.
 *
 * [retry] defaults to [RetryPolicy.None] — opting in to retry is explicit so
 * non-idempotent POSTs can't silently inherit a retry that double-spends.
 * See [RetryPolicy] header for the idempotency tradeoffs.
 *
 * Token-refresh on 401 still rides Ktor's `Auth` plugin (installed on
 * [NetworkClient.authenticatedClient]) — that's separate from this retry
 * loop and happens beneath it.
 *
 * Cancellation is preserved via [Catching] — `CancellationException` is
 * re-thrown rather than swallowed, and the retry loop's `delay` is
 * cancellable.
 */
suspend fun <T> NetworkClient.authedCall(
    description: String,
    retry: RetryPolicy = RetryPolicy.None,
    block: suspend (HttpClient) -> T,
): Catching<T> = withRetry(retry) {
    Catching { block(authenticatedClient) }
}.onFailure { throwable ->
    networkCallLogger.w(throwable) { "$description failed (${throwable.classifyForLog()})" }
}

/**
 * Unauthenticated counterpart to [authedCall]. Same retry/logging contract,
 * routes through [NetworkClient.client] for public endpoints (app-config
 * fetch, healthcheck, anything pre-session).
 */
suspend fun <T> NetworkClient.unauthedCall(
    description: String,
    retry: RetryPolicy = RetryPolicy.None,
    block: suspend (HttpClient) -> T,
): Catching<T> = withRetry(retry) {
    Catching { block(client) }
}.onFailure { throwable ->
    networkCallLogger.w(throwable) { "$description failed (${throwable.classifyForLog()})" }
}

private fun Throwable.classifyForLog(): String = when (this) {
    is HttpRequestTimeoutException -> "timeout"
    is ResponseException -> "http ${response.status.value}"
    else -> this::class.simpleName ?: "unknown"
}
