package com.dangerfield.cards.libraries.networking

import io.ktor.client.plugins.ResponseException
import io.ktor.http.HttpStatusCode

/**
 * True when this failure is the server correctly refusing a caller, rather than
 * anything going wrong. A `401` means re-authenticate and a `403` means the
 * account is blocked; both are answers the client already acts on, and both
 * arrive repeatedly by design while the condition lasts.
 *
 * Telemetry sinks drop these before they become error events. One banned user
 * produced a steady stream of `403`-shaped Sentry errors (CARDS-BG) that no one
 * could act on — the server was working, and the ban was already on screen.
 *
 * Deliberately narrow. Every other 4xx is still a real error: a `400` or a `409`
 * means the client sent something the server didn't expect, which is a bug worth
 * seeing. So is any 5xx.
 */
fun Throwable.isExpectedClientError(): Boolean {
    var current: Throwable? = this
    repeat(MAX_CAUSE_DEPTH) {
        val candidate = current ?: return false
        if (candidate is ResponseException && candidate.response.status in EXPECTED_REFUSALS) return true
        current = candidate.cause
    }
    return false
}

private val EXPECTED_REFUSALS = setOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)

private const val MAX_CAUSE_DEPTH = 8
