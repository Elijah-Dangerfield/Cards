package com.dangerfield.cards.libraries.networking.retry

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Variance added on top of a [Backoff] delay so synchronized retry storms
 * don't pile on the server when many clients hit the same transient
 * failure at the same moment. The strategies match the AWS Architecture
 * Blog naming for retry jitter (Equal, Full).
 *
 * [Random] is parameterized on [apply] so tests can pin the output;
 * production callers omit the argument and get `Random.Default`.
 */
sealed interface Jitter {

    fun apply(delay: Duration, random: Random = Random.Default): Duration

    /** No jitter — caller gets exactly the [Backoff] delay. */
    data object None : Jitter {
        override fun apply(delay: Duration, random: Random): Duration = delay
    }

    /**
     * Equal jitter: returns a uniform value in `[delay/2, delay * 3/2)`.
     * Centered on [delay] — preserves average backoff while spreading
     * actual retry times. Good default for most cases.
     */
    data object Equal : Jitter {
        override fun apply(delay: Duration, random: Random): Duration {
            if (delay == Duration.ZERO) return Duration.ZERO
            val ms = delay.inWholeMilliseconds
            val half = ms / 2
            val variance = random.nextLong(0, ms.coerceAtLeast(1))
            return (half + variance).milliseconds
        }
    }

    /**
     * Full jitter: returns a uniform value in `[0, delay)`. Maximum
     * spread; the average delay halves vs. [Equal]. Use when the
     * thundering-herd risk dominates the latency cost.
     */
    data object Full : Jitter {
        override fun apply(delay: Duration, random: Random): Duration {
            if (delay == Duration.ZERO) return Duration.ZERO
            val ms = delay.inWholeMilliseconds
            return random.nextLong(0, ms.coerceAtLeast(1)).milliseconds
        }
    }
}
