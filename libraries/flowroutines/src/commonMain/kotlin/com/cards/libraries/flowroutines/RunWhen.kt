package com.dangerfield.cards.libraries.flowroutines

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Run [work] while a condition holds, instead of when an event happens to be
 * observed. The fix for the lost-edge failure mode: an edge fired before the
 * subscriber attached is gone, but a level read at subscribe time is not.
 *
 * - [key] is the level: `null` = off, non-null = on. A non-null value at
 *   subscribe fires immediately, so late subscribers can't miss an
 *   already-true condition. A key *change* (by equality) cancels in-flight
 *   work — including a pending retry backoff — and fires fresh for the new
 *   key; a change to `null` just cancels.
 * - [refireOn] edges re-run [work] while the key is non-null and are ignored
 *   while it's null. Triggers are conflated: edges landing during a run
 *   coalesce into exactly one trailing run.
 * - A failed run ([work] returning failure or throwing a non-cancellation
 *   exception) retries on [retry]'s schedule while the key holds. Exhaustion
 *   stops the cycle; the next refire edge re-arms with a fresh counter.
 * - [work] runs directly in the per-key cycle scope: `coroutineContext.job`
 *   inside [work] is the cycle's job, and cancelling it externally kills the
 *   whole cycle — pending retries and refire edges included — while the loop
 *   stays alive for the next key. Callers that need to cancel-and-await a
 *   cycle from outside (e.g. quiescing a user's sync before wiping their
 *   data) register that job; pinned by a test.
 */
fun <K : Any> CoroutineScope.runWhen(
    key: Flow<K?>,
    refireOn: Flow<*> = emptyFlow<Unit>(),
    retry: RunWhenRetry = RunWhenRetry.None,
    work: suspend (K) -> Result<Unit>,
): Job = launch {
    key.distinctUntilChanged().collectLatest { current ->
        if (current == null) return@collectLatest
        coroutineScope {
            val triggers = Channel<Unit>(Channel.CONFLATED)
            triggers.trySend(Unit)
            launch { refireOn.collect { triggers.trySend(Unit) } }
            for (trigger in triggers) {
                runAttemptCycle(retry) { work(current) }
            }
        }
    }
}

/** Boolean convenience: `true` = on, `false` = off. Repeated `true`s don't refire. */
fun CoroutineScope.runWhen(
    condition: Flow<Boolean>,
    refireOn: Flow<*> = emptyFlow<Unit>(),
    retry: RunWhenRetry = RunWhenRetry.None,
    work: suspend () -> Result<Unit>,
): Job = runWhen(
    key = condition.map { if (it) Unit else null },
    refireOn = refireOn,
    retry = retry,
    work = { work() },
)

private suspend fun runAttemptCycle(retry: RunWhenRetry, attempt: suspend () -> Result<Unit>) {
    var failures = 0
    while (true) {
        val outcome = Catching { attempt().getOrThrow() }
        if (outcome.isSuccess) return
        if (failures >= retry.maxRetries) {
            if (retry.maxRetries > 0) {
                KLog.withTag("RunWhen").w {
                    "work still failing after ${failures + 1} attempts; waiting for the next trigger"
                }
            }
            return
        }
        delay(retry.delayFor(failures))
        failures++
    }
}

class RunWhenRetry(
    val maxRetries: Int,
    val delayFor: (attempt: Int) -> Duration,
) {
    companion object {
        val None = RunWhenRetry(maxRetries = 0) { Duration.ZERO }

        fun exponential(
            initial: Duration = 5.seconds,
            factor: Double = 2.0,
            max: Duration = 2.minutes,
            retries: Int = 5,
        ): RunWhenRetry = RunWhenRetry(maxRetries = retries) { attempt ->
            (initial * factor.pow(attempt)).coerceAtMost(max)
        }
    }
}
