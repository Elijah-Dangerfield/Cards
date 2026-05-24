package com.dangerfield.cards.libraries.networking.retry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BackoffTest {

    @Test
    fun none_alwaysReturnsZero() {
        repeat(10) { i ->
            assertEquals(Duration.ZERO, Backoff.None.delayFor(attempt = i + 1))
        }
    }

    @Test
    fun fixed_returnsSameDelay_regardlessOfAttempt() {
        val backoff = Backoff.Fixed(750.milliseconds)
        assertEquals(750.milliseconds, backoff.delayFor(1))
        assertEquals(750.milliseconds, backoff.delayFor(5))
        assertEquals(750.milliseconds, backoff.delayFor(100))
    }

    @Test
    fun linear_scalesByAttempt() {
        val backoff = Backoff.Linear(step = 500.milliseconds)
        assertEquals(500.milliseconds, backoff.delayFor(1))
        assertEquals(1000.milliseconds, backoff.delayFor(2))
        assertEquals(2500.milliseconds, backoff.delayFor(5))
    }

    @Test
    fun exponential_growsByFactor() {
        val backoff = Backoff.Exponential(initial = 100.milliseconds, factor = 2.0, max = 10.seconds)
        assertEquals(100.milliseconds, backoff.delayFor(1))
        assertEquals(200.milliseconds, backoff.delayFor(2))
        assertEquals(400.milliseconds, backoff.delayFor(3))
        assertEquals(800.milliseconds, backoff.delayFor(4))
    }

    @Test
    fun exponential_capsAtMax() {
        val backoff = Backoff.Exponential(initial = 500.milliseconds, factor = 2.0, max = 2.seconds)
        // 500ms → 1s → 2s (cap) → 2s (cap) → 2s (cap)
        assertEquals(500.milliseconds, backoff.delayFor(1))
        assertEquals(1000.milliseconds, backoff.delayFor(2))
        assertEquals(2000.milliseconds, backoff.delayFor(3))
        assertEquals(2000.milliseconds, backoff.delayFor(4))
        assertEquals(2000.milliseconds, backoff.delayFor(50))
    }

    @Test
    fun exponential_pathologicalAttemptCount_doesNotOverflow() {
        val backoff = Backoff.Exponential(initial = 1.seconds, factor = 2.0, max = 60.seconds)
        // Without the millis-Double computation this would overflow Long
        // nanos around attempt ~40. The cap should hold.
        assertEquals(60.seconds, backoff.delayFor(100))
    }

    @Test
    fun exponential_validatesFactor() {
        assertFailsWith<IllegalArgumentException> {
            Backoff.Exponential(initial = 1.seconds, factor = 0.5, max = 10.seconds)
        }
    }

    @Test
    fun exponential_validatesInitialNotGreaterThanMax() {
        assertFailsWith<IllegalArgumentException> {
            Backoff.Exponential(initial = 10.seconds, factor = 2.0, max = 5.seconds)
        }
    }
}
