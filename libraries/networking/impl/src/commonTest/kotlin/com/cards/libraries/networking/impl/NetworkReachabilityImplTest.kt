package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the "one failure is noise, a run of failures is offline" contract:
 *  - a single failed round-trip does NOT flip reachable (avoids a banner flash
 *    on one flaky request),
 *  - the threshold (2 in a row) does,
 *  - any success resets the run and recovers immediately.
 */
class NetworkReachabilityImplTest : CoroutineTest() {

    @Test
    fun startsReachable() = runUnitTest {
        val r = NetworkReachabilityImpl(AppCoroutineScope(dispatchers))
        advanceUntilIdle()
        assertEquals(true, r.isReachable.value)
    }

    @Test
    fun singleFailure_staysReachable() = runUnitTest {
        val r = NetworkReachabilityImpl(AppCoroutineScope(dispatchers))
        r.reportUnreachable()
        advanceUntilIdle()
        assertEquals(true, r.isReachable.value, "one failure is noise — don't flip")
    }

    @Test
    fun twoConsecutiveFailures_becomeUnreachable() = runUnitTest {
        val r = NetworkReachabilityImpl(AppCoroutineScope(dispatchers))
        r.reportUnreachable()
        r.reportUnreachable()
        advanceUntilIdle()
        assertEquals(false, r.isReachable.value)
    }

    @Test
    fun successResetsTheRun_recoversImmediately() = runUnitTest {
        val r = NetworkReachabilityImpl(AppCoroutineScope(dispatchers))
        r.reportUnreachable()
        r.reportUnreachable()
        advanceUntilIdle()
        assertEquals(false, r.isReachable.value)

        r.reportReachable()
        advanceUntilIdle()
        assertEquals(true, r.isReachable.value, "a successful round-trip clears it")
    }

    @Test
    fun successBetweenFailures_keepsReachable() = runUnitTest {
        val r = NetworkReachabilityImpl(AppCoroutineScope(dispatchers))
        r.reportUnreachable()
        r.reportReachable() // resets the run
        r.reportUnreachable() // only one in the new run
        advanceUntilIdle()
        assertEquals(true, r.isReachable.value, "non-consecutive failures never reach the threshold")
    }
}
