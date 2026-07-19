package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.domain.UserId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The relaxed-grant throttle: a best-effort per-user cap on grant-on-replay,
 * distinct per user and windowed. Idempotency is the real double-grant guard;
 * this only bounds and surfaces abuse (see `docs/wiki/purchases.md`).
 */
@OptIn(ExperimentalTime::class)
class RelaxedGrantRateLimiterTest {

    private val userA = UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val userB = UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"))

    private class MutableClock(var now: Instant) : Clock {
        override fun now(): Instant = now
    }

    @Test
    fun allowsUpToTheCap_thenRefuses() {
        val clock = MutableClock(Instant.fromEpochMilliseconds(0))
        val limiter = RelaxedGrantRateLimiter(clock)

        repeat(RelaxedGrantRateLimiter.MAX_PER_WINDOW) {
            assertTrue(limiter.tryAcquire(userA), "grant $it within the cap is allowed")
        }
        assertFalse(limiter.tryAcquire(userA), "the grant past the cap is refused")
    }

    @Test
    fun theCapIsPerUser() {
        val clock = MutableClock(Instant.fromEpochMilliseconds(0))
        val limiter = RelaxedGrantRateLimiter(clock)

        repeat(RelaxedGrantRateLimiter.MAX_PER_WINDOW) { limiter.tryAcquire(userA) }
        assertFalse(limiter.tryAcquire(userA), "user A is capped")
        assertTrue(limiter.tryAcquire(userB), "user B has their own budget")
    }

    @Test
    fun theWindowSlidesForwardAsTimePasses() {
        val clock = MutableClock(Instant.fromEpochMilliseconds(0))
        val limiter = RelaxedGrantRateLimiter(clock)

        repeat(RelaxedGrantRateLimiter.MAX_PER_WINDOW) { limiter.tryAcquire(userA) }
        assertFalse(limiter.tryAcquire(userA), "capped inside the window")

        clock.now = clock.now + RelaxedGrantRateLimiter.WINDOW + 1.hours
        assertTrue(limiter.tryAcquire(userA), "the old grants aged out of the window")
    }
}
