package com.dangerfield.cards.libraries.networking.retry

import com.dangerfield.cards.libraries.core.Catching
import kotlinx.coroutines.delay

/**
 * Run [block] under [policy]: on failure, consult [RetryPolicy.retryIf] and
 * either return immediately or wait (per [RetryPolicy.backoff] + jitter) and
 * try again, up to [RetryPolicy.maxAttempts] total. The first success short-
 * circuits the loop.
 *
 * [block] returns a [Catching] (not a raw value) so the caller can run
 * arbitrary logic inside `Catching { … }` — the failure side is opaque to
 * this loop, only the predicate inspects it. `CancellationException` never
 * reaches the predicate because `Catching` rethrows it; the surrounding
 * `delay` is also cancellable, so a cancelled scope unwinds promptly.
 *
 * The returned value is the last attempt's result — success if any attempt
 * succeeded, otherwise the failure of the final attempt.
 */
suspend fun <T> withRetry(
    policy: RetryPolicy,
    block: suspend () -> Catching<T>,
): Catching<T> {
    var last: Catching<T>? = null
    repeat(policy.maxAttempts) { attempt ->
        if (attempt > 0) {
            val base = policy.backoff.delayFor(attempt)
            val withJitter = policy.jitter.apply(base)
            if (withJitter.isPositive()) delay(withJitter)
        }
        val result = block()
        if (result.isSuccess) return result
        last = result
        val failure = result.exceptionOrNull() ?: error("isFailure with no exception")
        val isFinalAttempt = attempt == policy.maxAttempts - 1
        if (isFinalAttempt || !policy.retryIf(failure)) return result
    }
    // repeat with maxAttempts >= 1 always returns inside the loop; this is unreachable.
    return last ?: error("repeat exited without producing a result — maxAttempts must be >= 1")
}
