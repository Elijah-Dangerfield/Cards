package com.dangerfield.cards.libraries.networking

import com.dangerfield.cards.libraries.core.isExpectedControlFlow

/**
 * The one answer to "would an error-level report about this tell us anything?"
 *
 * Three things say no, and the codebase had already decided all three
 * separately:
 *
 *  - [isExpectedControlFlow] — a typed short-circuit the caller is meant to
 *    handle, `AuthUnready` being the reference case.
 *  - [isOfflineError] — the device has no network. One phone in a tunnel
 *    produced 59 error events in 43 minutes (ENG-34).
 *  - [isExpectedClientError] — the server correctly refusing the caller with a
 *    `401` or `403` (ENG-35).
 *
 * It lives here rather than beside [isExpectedControlFlow] in `:libraries:core`,
 * where ENG-44 proposed it, because two of the three predicates need Ktor and
 * the platform socket types, and `:libraries:core` sits underneath both.
 *
 * **Every telemetry sink consults this and only this.** Before, each tree
 * carried its own opinion: Sentry honoured the marker's contract while the
 * Grafana tree gated on level alone, so the same throwable was dropped by one
 * and shipped at full severity by the other. 85% of prod client ERROR lines were
 * `AuthUnready` — in the exact panel the nightly triage reads to find real bugs.
 *
 * A "no" here means **downgrade**, not delete. These lines are genuinely useful
 * at DEBUG when reconstructing a session after the fact; what they must not do
 * is sit in the error tier.
 */
fun Throwable.isExpectedFailure(): Boolean =
    isExpectedControlFlow || isOfflineError() || isExpectedClientError()

/**
 * Whether a failure carrying no HTTP response is evidence the backend couldn't
 * be reached.
 *
 * "No response" is normally exactly that evidence — a timeout, a refused
 * connection, DNS, a captive portal. A typed short-circuit is the exception: the
 * client *chose* not to send, so the request's absence says nothing about the
 * server. [com.dangerfield.cards.libraries.networking.impl.BannedShortCircuit]
 * is the case that made this explicit — counting it flipped a banned user to
 * "offline" after two blocked calls, and since the breaker suppresses every
 * non-allowlisted call, nothing was left to report reachable and clear the run.
 *
 * Callers should already have handled the has-a-response case; this only decides
 * what an empty-handed failure means.
 */
fun Throwable.indicatesBackendUnreachable(): Boolean = !isExpectedControlFlow
