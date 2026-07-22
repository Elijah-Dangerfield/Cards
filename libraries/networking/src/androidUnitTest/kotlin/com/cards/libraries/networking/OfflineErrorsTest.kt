package com.dangerfield.cards.libraries.networking

import io.ktor.client.plugins.HttpRequestTimeoutException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ENG-34: the JVM/Android side of the device-offline classifier. The line it
 * draws: failures only the device's connectivity explains are offline; anything
 * the backend could be responsible for stays reportable.
 */
class OfflineErrorsTest {

    @Test
    fun `dns and no-route failures are offline`() {
        assertTrue(UnknownHostException("Unable to resolve host").isOfflineError())
        assertTrue(NoRouteToHostException("No route to host").isOfflineError())
        assertTrue(ConnectException("Network is unreachable").isOfflineError())
        assertTrue(SocketException("Network is unreachable").isOfflineError())
    }

    @Test
    fun `a wrapped offline cause is still offline`() {
        val wrapped = IllegalStateException("sync failed", RuntimeException(UnknownHostException("no DNS")))
        assertTrue(wrapped.isOfflineError())
    }

    @Test
    fun `backend-attributable failures are not offline`() {
        assertFalse(ConnectException("Connection refused").isOfflineError())
        assertFalse(HttpRequestTimeoutException("/v1/equipment/sync", 15_000).isOfflineError())
        assertFalse(IllegalStateException("boom").isOfflineError())
    }

    @Test
    fun `a cause cycle terminates instead of looping`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertFalse(a.isOfflineError())
    }
}
