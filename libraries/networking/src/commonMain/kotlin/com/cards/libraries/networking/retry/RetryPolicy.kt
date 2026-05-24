package com.dangerfield.cards.libraries.networking.retry

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.utils.io.errors.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Immutable retry policy passed to `withRetry` / `NetworkClient.authedCall` /
 * `NetworkClient.unauthedCall`. Built with a fluent chain — every wither
 * returns a new instance:
 *
 * ```
 * val policy = RetryPolicy.exponential().withJitter().maxRetries(5)
 * ```
 *
 * **Defaults you should know:**
 *  - [maxAttempts] starts at 1 — i.e. no retries. Opting in is explicit so a
 *    repository accidentally inheriting retry on a non-idempotent POST is
 *    structurally impossible.
 *  - The default [retryIf] predicate retries on transient network failures
 *    only (timeout, 5xx, IO). 4xx (client error) is NOT retried — the server
 *    already processed and rejected the request, the same payload won't
 *    succeed on the next try.
 *  - Cancellation propagates through `Catching {}`'s `shouldNotBeCaught`, so
 *    the loop never spuriously retries a coroutine that was just cancelled.
 *
 * **On idempotency:** retry is a footgun for non-idempotent POSTs (chip
 * purchases, wallet sync). If the server processed your request but the
 * response leg dropped, a retry double-spends. Two ways to stay safe:
 *  1. Don't pass a policy — `RetryPolicy.None` is the default. POSTs to
 *     non-idempotent endpoints just leave it alone.
 *  2. Narrow [retryIf] to network-stack failures only (timeout,
 *     IOException) — those are the cases where the server didn't see the
 *     request at all. Skip [ResponseException] (the server *did* see it).
 *
 * **On offline-aware retry:** the policy retries on [HttpRequestTimeoutException]
 * by default, which doesn't actually tell you whether the user is offline or
 * the server is slow. A true offline-aware retry would defer the call until
 * connectivity returns (different shape — a deferred queue, not a tight
 * retry loop). Filed as backlog; for now treat timeouts as "try again soon"
 * with the cap that exponential backoff gives you.
 */
class RetryPolicy private constructor(
    val maxAttempts: Int,
    val backoff: Backoff,
    val jitter: Jitter,
    val retryIf: (Throwable) -> Boolean,
) {

    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1, was $maxAttempts" }
    }

    /**
     * Replace the maximum number of retries. Total attempts will be
     * `retries + 1` (the initial call plus [retries] retries).
     */
    fun maxRetries(retries: Int): RetryPolicy {
        require(retries >= 0) { "retries must be >= 0, was $retries" }
        return copy(maxAttempts = retries + 1)
    }

    /** Replace the backoff strategy. */
    fun backoff(strategy: Backoff): RetryPolicy = copy(backoff = strategy)

    /**
     * Apply the given jitter strategy. Defaults to [Jitter.Equal] when no
     * argument is supplied — the most useful preset.
     */
    fun withJitter(strategy: Jitter = Jitter.Equal): RetryPolicy = copy(jitter = strategy)

    /**
     * Replace the retry predicate. The predicate decides per-failure
     * whether to retry; returning `false` shortcuts the loop and returns
     * the failure unchanged.
     *
     * `CancellationException` is never seen by this predicate — `Catching`
     * rethrows it before we ever reach a retry decision.
     */
    fun retryIf(predicate: (Throwable) -> Boolean): RetryPolicy = copy(retryIf = predicate)

    private fun copy(
        maxAttempts: Int = this.maxAttempts,
        backoff: Backoff = this.backoff,
        jitter: Jitter = this.jitter,
        retryIf: (Throwable) -> Boolean = this.retryIf,
    ): RetryPolicy = RetryPolicy(maxAttempts, backoff, jitter, retryIf)

    companion object {
        /** No retries — the call fires once, the result returns as-is. */
        val None: RetryPolicy = RetryPolicy(
            maxAttempts = 1,
            backoff = Backoff.None,
            jitter = Jitter.None,
            retryIf = ::isTransientNetworkFailure,
        )

        /**
         * Starter with exponential backoff. Defaults: 500ms initial, factor 2,
         * cap 10s, 3 retries (4 attempts total), [Jitter.None]. Chain on
         * [withJitter] / [maxRetries] / [retryIf] as needed.
         */
        fun exponential(
            initial: Duration = 500.milliseconds,
            factor: Double = 2.0,
            max: Duration = 10.seconds,
            retries: Int = 3,
        ): RetryPolicy = RetryPolicy(
            maxAttempts = retries + 1,
            backoff = Backoff.Exponential(initial = initial, factor = factor, max = max),
            jitter = Jitter.None,
            retryIf = ::isTransientNetworkFailure,
        )

        /** Starter with linear backoff. Defaults: 500ms step, 3 retries. */
        fun linear(
            step: Duration = 500.milliseconds,
            retries: Int = 3,
        ): RetryPolicy = RetryPolicy(
            maxAttempts = retries + 1,
            backoff = Backoff.Linear(step = step),
            jitter = Jitter.None,
            retryIf = ::isTransientNetworkFailure,
        )

        /** Starter with fixed delay between attempts. */
        fun fixed(
            delay: Duration,
            retries: Int = 3,
        ): RetryPolicy = RetryPolicy(
            maxAttempts = retries + 1,
            backoff = Backoff.Fixed(delay = delay),
            jitter = Jitter.None,
            retryIf = ::isTransientNetworkFailure,
        )

        /**
         * The recommended preset for **idempotent endpoints** — exponential
         * backoff with equal jitter, retrying transient network failures
         * (timeout / 5xx / IO). 3 retries (4 attempts total).
         *
         * The factory's *name* encodes the precondition: by reaching for
         * `RetryPolicy.idempotent()`, the caller is affirming that the
         * server tolerates the same request landing more than once
         * (idempotency keys, upserts, snapshot reconciliation, etc.). If
         * that isn't true for your endpoint, leave [retry] at
         * [RetryPolicy.None] — a duplicate POST that the server processed
         * twice double-spends.
         *
         * See [RetryPolicy] header for the broader idempotency tradeoffs.
         */
        fun idempotent(): RetryPolicy = exponential().withJitter()
    }
}

/**
 * The default [RetryPolicy.retryIf] predicate. Returns true for failures
 * that are *likely* to succeed if retried: request timeout, 5xx responses
 * (server overload / temporary fault), and lower-level IO failures
 * (connection reset, DNS hiccup).
 *
 * 4xx is intentionally NOT retried — the server processed the request and
 * said no. Retrying the same payload won't change the answer.
 */
fun isTransientNetworkFailure(t: Throwable): Boolean = when (t) {
    is HttpRequestTimeoutException -> true
    is ResponseException -> t.response.status.value in 500..599
    is IOException -> true
    else -> false
}
