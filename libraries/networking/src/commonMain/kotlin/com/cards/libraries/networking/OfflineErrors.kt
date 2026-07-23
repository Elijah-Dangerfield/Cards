package com.dangerfield.cards.libraries.networking

/**
 * True when this failure (or one of its causes) means the *device* has no
 * usable network — airplane mode, dead spot, cellular data off — rather than
 * the backend failing. Telemetry sinks use it to keep expected offline churn
 * out of error counts (ENG-34: one phone in a tunnel produced 59 error events
 * in 43 minutes). Failures the server could be responsible for — timeouts,
 * refused connections, HTTP statuses — never match, so a real outage still
 * reports.
 */
fun Throwable.isOfflineError(): Boolean {
    var current: Throwable? = this
    repeat(MAX_CAUSE_DEPTH) {
        val candidate = current ?: return false
        if (isPlatformOfflineError(candidate)) return true
        current = candidate.cause
    }
    return false
}

internal expect fun isPlatformOfflineError(throwable: Throwable): Boolean

private const val MAX_CAUSE_DEPTH = 8
