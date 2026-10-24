package com.dangerfield.cards.libraries.networking

import io.ktor.client.plugins.ResponseException
import io.ktor.http.HttpStatusCode

/**
 * True when this failure is the server correctly refusing a caller, rather than
 * anything going wrong. A `401` means re-authenticate, a `403` means the
 * account is blocked, a `429` means slow down; all three are answers the client
 * already acts on, and all three arrive repeatedly by design while the
 * condition lasts.
 *
 * Telemetry sinks drop these before they become error events. One banned user
 * produced a steady stream of `403`-shaped Sentry errors (CARDS-BG) that no one
 * could act on — the server was working, and the ban was already on screen. A
 * rate limit has the same shape: the server is healthy and applying the
 * back-pressure it was configured to apply (ENG-68). The place to watch rate
 * limiting is the server, which knows which bucket filled; the client only
 * knows it was asked to wait.
 *
 * Deliberately narrow. Every other 4xx is still a real error: a `400` or a `409`
 * means the client sent something the server didn't expect, which is a bug worth
 * seeing. So is any 5xx.
 */
fun Throwable.isExpectedClientError(): Boolean = hasResponseStatusIn(EXPECTED_REFUSALS)

/**
 * True when the server asked the caller to back off (`429`). Distinct from
 * [isExpectedClientError] because callers do more than downgrade a log line
 * with it: a background syncer that retries into a bucket it just exhausted
 * spends its whole ladder on requests the server has already promised to
 * refuse.
 */
fun Throwable.isRateLimited(): Boolean = hasResponseStatusIn(RATE_LIMITED)

private fun Throwable.hasResponseStatusIn(statuses: Set<HttpStatusCode>): Boolean {
    var current: Throwable? = this
    repeat(MAX_CAUSE_DEPTH) {
        val candidate = current ?: return false
        if (candidate is ResponseException && candidate.response.status in statuses) return true
        current = candidate.cause
    }
    return false
}

private val RATE_LIMITED = setOf(HttpStatusCode.TooManyRequests)

private val EXPECTED_REFUSALS = setOf(
    HttpStatusCode.Unauthorized,
    HttpStatusCode.Forbidden,
) + RATE_LIMITED

private const val MAX_CAUSE_DEPTH = 8
