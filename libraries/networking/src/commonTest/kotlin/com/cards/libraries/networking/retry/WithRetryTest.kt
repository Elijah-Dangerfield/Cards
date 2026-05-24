package com.dangerfield.cards.libraries.networking.retry

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Standard scheduler (not the default Unconfined) so we can pin `delay()`
 * progression against virtual time.
 */
class WithRetryTest : CoroutineTest() {
    override val testDispatcher: TestDispatcher = StandardTestDispatcher()


    @Test
    fun successOnFirstAttempt_doesNotRetry() = runUnitTest {
        var calls = 0
        val result = withRetry(RetryPolicy.fixed(1.seconds)) {
            calls++
            Catching { "ok" }
        }
        assertEquals(1, calls)
        assertEquals("ok", result.getOrThrow())
    }

    @Test
    fun failureThenSuccess_returnsSuccess() = runUnitTest {
        var calls = 0
        val deferred = async {
            withRetry(RetryPolicy.fixed(100.milliseconds).maxRetries(3)) {
                calls++
                if (calls == 1) {
                    Catching { throw io.ktor.client.plugins.HttpRequestTimeoutException("u", 100) }
                } else {
                    Catching { "ok" }
                }
            }
        }
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(100); runCurrent()
        assertEquals(2, calls)
        val result = deferred.await()
        assertEquals("ok", result.getOrThrow())
    }

    @Test
    fun exhaustsMaxAttempts_returnsFinalFailure() = runUnitTest {
        var calls = 0
        val sentinel = io.ktor.client.plugins.HttpRequestTimeoutException("u", 100)
        val deferred = async {
            withRetry(RetryPolicy.fixed(100.milliseconds).maxRetries(2)) {
                calls++
                Catching { throw sentinel }
            }
        }
        advanceUntilIdle()
        val result = deferred.await()
        assertEquals(3, calls, "1 initial + 2 retries = 3 attempts")
        assertTrue(result.isFailure)
        assertSame(sentinel, result.exceptionOrNull())
    }

    @Test
    fun retryIfReturnsFalse_returnsFailureWithoutFurtherAttempts() = runUnitTest {
        var calls = 0
        val sentinel = IllegalStateException("don't retry me")
        val policy = RetryPolicy.fixed(100.milliseconds).maxRetries(5).retryIf { false }
        val result = withRetry(policy) {
            calls++
            Catching { throw sentinel }
        }
        assertEquals(1, calls, "predicate returned false → no retries")
        assertSame(sentinel, result.exceptionOrNull())
    }

    @Test
    fun retryIfTrue_butFinalAttempt_stillReturns() = runUnitTest {
        var calls = 0
        val sentinel = RuntimeException("always retry me")
        val deferred = async {
            withRetry(RetryPolicy.fixed(50.milliseconds).maxRetries(1).retryIf { true }) {
                calls++
                Catching { throw sentinel }
            }
        }
        advanceUntilIdle()
        val result = deferred.await()
        assertEquals(2, calls, "1 initial + 1 retry = 2 attempts (predicate was true, but loop is exhausted)")
        assertSame(sentinel, result.exceptionOrNull())
    }

    @Test
    fun retryDelays_followBackoffProgression() = runUnitTest {
        var calls = 0
        val policy = RetryPolicy.linear(step = 100.milliseconds).maxRetries(3)
        val deferred = async {
            withRetry(policy) {
                calls++
                Catching { throw io.ktor.client.plugins.HttpRequestTimeoutException("u", 1) }
            }
        }

        runCurrent(); assertEquals(1, calls)
        // Linear backoff before retry #1 (attempt index = 1) = 100ms.
        advanceTimeBy(99); runCurrent(); assertEquals(1, calls)
        advanceTimeBy(1); runCurrent(); assertEquals(2, calls)
        // Before retry #2 (attempt index = 2) = 200ms.
        advanceTimeBy(199); runCurrent(); assertEquals(2, calls)
        advanceTimeBy(1); runCurrent(); assertEquals(3, calls)
        // Before retry #3 (attempt index = 3) = 300ms.
        advanceTimeBy(299); runCurrent(); assertEquals(3, calls)
        advanceTimeBy(1); runCurrent(); assertEquals(4, calls)
        deferred.await()
    }

    @Test
    fun cancellationDuringBlock_propagates_doesNotInvokeRetryIf() = runUnitTest {
        var calls = 0
        var predicateCalls = 0
        val policy = RetryPolicy.fixed(100.milliseconds).maxRetries(5).retryIf {
            predicateCalls++
            true
        }

        assertFailsWith<CancellationException> {
            withRetry(policy) {
                calls++
                // Catching rethrows CancellationException via shouldNotBeCaught,
                // so it escapes the loop unwrapped. The predicate must never see it.
                Catching { throw CancellationException("scope cancelled") }
            }
        }
        assertEquals(1, calls)
        assertEquals(0, predicateCalls, "predicate must not be consulted on cancellation")
    }

    @Test
    fun noBackoff_attemptsAreImmediate_noVirtualTimeAdvanced() = runUnitTest {
        var calls = 0
        val deferred = async {
            withRetry(RetryPolicy.None.maxRetries(3).retryIf { true }) {
                calls++
                Catching { throw RuntimeException("nope") }
            }
        }
        runCurrent()
        // None backoff = no delay between attempts; all 4 fire in the same virtual tick.
        assertEquals(4, calls)
        deferred.await()
    }
}
