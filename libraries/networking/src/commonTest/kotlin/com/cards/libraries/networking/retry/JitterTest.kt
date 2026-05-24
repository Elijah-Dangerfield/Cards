package com.dangerfield.cards.libraries.networking.retry

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class JitterTest {

    @Test
    fun none_returnsDelayUnchanged() {
        assertEquals(800.milliseconds, Jitter.None.apply(800.milliseconds))
        assertEquals(Duration.ZERO, Jitter.None.apply(Duration.ZERO))
    }

    @Test
    fun equal_returnsValueInHalfToOneAndAHalfRange() {
        val delay = 1000.milliseconds
        // Walk a seeded random so the assertion isn't flaky.
        val random = Random(seed = 42)
        repeat(200) {
            val jittered = Jitter.Equal.apply(delay, random)
            assertTrue(
                jittered >= 500.milliseconds && jittered < 1500.milliseconds,
                "Equal jitter must land in [delay/2, delay*1.5); got $jittered",
            )
        }
    }

    @Test
    fun full_returnsValueInZeroToDelayRange() {
        val delay = 1000.milliseconds
        val random = Random(seed = 7)
        repeat(200) {
            val jittered = Jitter.Full.apply(delay, random)
            assertTrue(
                jittered >= Duration.ZERO && jittered < 1000.milliseconds,
                "Full jitter must land in [0, delay); got $jittered",
            )
        }
    }

    @Test
    fun equal_andFull_returnZero_whenDelayIsZero() {
        // Zero base delay means no backoff configured — jitter must not
        // synthesize delay out of nothing (and Random.nextLong(0, 0) would
        // throw if we didn't guard it).
        assertEquals(Duration.ZERO, Jitter.Equal.apply(Duration.ZERO))
        assertEquals(Duration.ZERO, Jitter.Full.apply(Duration.ZERO))
    }
}
