package com.dangerfield.cards.libraries.networking.retry

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Delay between retry attempts. Stateless — given an attempt number (1 for the
 * first retry, 2 for the second, …), returns the base delay before jitter is
 * applied. `attempt = 0` is the initial call; [delayFor] is not consulted for
 * it.
 *
 * The strategies are sealed so a misbehaving caller can't introduce a
 * monotonically-growing or unbounded delay; if you need something new, add a
 * data class here and we agree on its bounds together.
 */
sealed interface Backoff {

    /** Returns the base delay before the given attempt. [attempt] starts at 1. */
    fun delayFor(attempt: Int): Duration

    /** No delay — attempts fire back-to-back. Useful only for very fast tests. */
    data object None : Backoff {
        override fun delayFor(attempt: Int): Duration = Duration.ZERO
    }

    /** Constant delay regardless of attempt number. */
    data class Fixed(val delay: Duration) : Backoff {
        override fun delayFor(attempt: Int): Duration = delay
    }

    /** Delay grows linearly: `step * attempt`. */
    data class Linear(val step: Duration) : Backoff {
        override fun delayFor(attempt: Int): Duration = step * attempt
    }

    /**
     * Delay grows geometrically: `initial * factor^(attempt - 1)`, capped at
     * [max]. The cap exists so a long-running retry policy doesn't compound
     * past the server's read timeout / the user's patience.
     */
    data class Exponential(
        val initial: Duration,
        val factor: Double = 2.0,
        val max: Duration,
    ) : Backoff {
        init {
            require(factor >= 1.0) { "factor must be >= 1.0, was $factor" }
            require(initial <= max) { "initial ($initial) must be <= max ($max)" }
        }

        override fun delayFor(attempt: Int): Duration {
            if (attempt <= 1) return initial.coerceAtMost(max)
            // Compute in millis-as-Double so we don't overflow Long nanos on a
            // pathological attempt count. The cap brings it back into safe
            // range before conversion.
            val baseMs = initial.inWholeMilliseconds.toDouble() * pow(factor, attempt - 1)
            val cappedMs = baseMs.coerceAtMost(max.inWholeMilliseconds.toDouble())
            return cappedMs.toLong().milliseconds
        }
    }
}

private fun pow(base: Double, exp: Int): Double {
    var result = 1.0
    repeat(exp) { result *= base }
    return result
}
