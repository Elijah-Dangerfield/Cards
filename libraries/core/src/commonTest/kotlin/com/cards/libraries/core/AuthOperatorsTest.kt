package com.dangerfield.cards.libraries.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Pins the [Catching] auth operators: they act on [AuthUnready] failures only —
 * successes and real failures (timeouts, 5xx, anything else) pass through
 * untouched.
 */
class AuthOperatorsTest {

    @Test
    fun mapAuthFailure_mapsAnAuthUnreadyFailureToAValue() {
        val result = failure(AuthUnready(AuthReason.Offline))
            .mapAuthFailure { reason -> "mapped:$reason" }

        assertEquals("mapped:Offline", result.getOrNull())
    }

    @Test
    fun mapAuthFailure_passesRealFailuresThrough() {
        val boom = IllegalStateException("boom")
        val result = failure(boom).mapAuthFailure { "mapped" }

        assertSame(boom, result.exceptionOrNull())
    }

    @Test
    fun mapAuthFailure_passesSuccessThrough() {
        val result = "value".success().mapAuthFailure { "mapped" }

        assertEquals("value", result.getOrNull())
    }

    @Test
    fun onAuthFailure_firesForAuthUnready_andPassesTheCatchingThrough() {
        var seen: AuthReason? = null
        val original: Catching<String> = failure(AuthUnready(AuthReason.SessionExpired))

        val result = original.onAuthFailure { seen = it }

        assertEquals(AuthReason.SessionExpired, seen)
        assertSame(original.exceptionOrNull(), result.exceptionOrNull())
    }

    @Test
    fun onAuthFailure_ignoresRealFailuresAndSuccess() {
        var fired = false

        failure(IllegalStateException("boom")).onAuthFailure { fired = true }
        "value".success().onAuthFailure { fired = true }

        assertEquals(false, fired)
        assertNull("value".success().onAuthFailure { }.exceptionOrNull())
    }
}
