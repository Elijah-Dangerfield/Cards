package com.dangerfield.cards.libraries.networking

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.networking.retry.RetryPolicy
import com.dangerfield.cards.libraries.networking.retry.withRetry
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.HttpRequestBuilder

private val networkCallLogger = KLog.withTag("NetworkCall")

/**
 * Authenticated HTTP call wrapper. Suspends until the auth subsystem has
 * resolved a session (via [NetworkClient.awaitAuthReady]), then hands the
 * authenticated [HttpClient] to [block], wraps each attempt in [Catching],
 * and emits a structured failure log with a classification tag (timeout /
 * http-status / exception-class).
 *
 * Why pre-flight await: Ktor's per-request timeout starts the moment the
 * call enters the bearer plugin, *including* any wait the plugin does for
 * a token. On a slow first-launch auth bootstrap, that ate most of the
 * 30s budget before the actual server roundtrip even started. Doing the
 * await here means the timeout clock only runs against the real network
 * request.
 *
 * [description] is a short, stable identifier for the call — used as the
 * log message prefix. Pick `"inventory.sync"` or `"wallet.fetch"`, not a
 * sentence; logs aggregate by description.
 *
 * [retry] defaults to [RetryPolicy.None] — opting in to retry is explicit
 * so non-idempotent POSTs can't silently inherit a retry that
 * double-spends. See [RetryPolicy] header for the idempotency tradeoffs.
 *
 * Cancellation is preserved via [Catching] — `CancellationException` is
 * re-thrown rather than swallowed, and the retry loop's `delay` is
 * cancellable.
 */
@OptIn(InternalNetworkingApi::class)
suspend fun <T> NetworkClient.authedCall(
    description: String,
    retry: RetryPolicy = RetryPolicy.None,
    block: suspend (HttpClient) -> T,
): Catching<T> {
    awaitAuthReady()
    return withRetry(retry) {
        Catching { block(authenticatedClient) }
    }.onFailure { throwable ->
        networkCallLogger.w(throwable) { "$description failed (${throwable.classifyForLog()})" }
    }
}

/**
 * Unauthenticated counterpart to [authedCall]. Same retry/logging contract,
 * routes through [NetworkClient.client] for public endpoints (app-config
 * fetch, healthcheck, anything pre-session). Does NOT call
 * [NetworkClient.awaitAuthReady] — public endpoints don't need auth, and
 * waiting for it would defeat the purpose of having an unauthenticated
 * client at all.
 */
@OptIn(InternalNetworkingApi::class)
suspend fun <T> NetworkClient.unauthedCall(
    description: String,
    retry: RetryPolicy = RetryPolicy.None,
    block: suspend (HttpClient) -> T,
): Catching<T> = withRetry(retry) {
    Catching { block(client) }
}.onFailure { throwable ->
    networkCallLogger.w(throwable) { "$description failed (${throwable.classifyForLog()})" }
}

/**
 * Authenticated WebSocket upgrade. Same pre-flight [awaitAuthReady] as
 * [authedCall], then opens a [DefaultClientWebSocketSession] via Ktor's
 * `webSocketSession` builder. Failure surfaces in the returned [Catching];
 * the caller owns the session lifecycle from there.
 *
 * Retry isn't a parameter here — the reconnect-on-drop loop lives at a
 * higher layer (e.g. `ReconnectingRoomSocket`), where it can coordinate
 * with the WebSocket's lifecycle (close vs. error vs. backoff).
 */
@OptIn(InternalNetworkingApi::class)
suspend fun NetworkClient.authedWebSocketSession(
    description: String,
    builder: HttpRequestBuilder.() -> Unit,
): Catching<DefaultClientWebSocketSession> {
    awaitAuthReady()
    return Catching {
        authenticatedClient.webSocketSession(block = builder)
    }.onFailure { throwable ->
        networkCallLogger.w(throwable) { "$description failed (${throwable.classifyForLog()})" }
    }
}

private fun Throwable.classifyForLog(): String = when (this) {
    is HttpRequestTimeoutException -> "timeout"
    is ResponseException -> "http ${response.status.value}"
    else -> this::class.simpleName ?: "unknown"
}
