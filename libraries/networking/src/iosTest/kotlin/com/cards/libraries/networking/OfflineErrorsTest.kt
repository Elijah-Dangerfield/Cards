package com.dangerfield.cards.libraries.networking

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import platform.Foundation.NSError
import platform.Foundation.NSURLErrorCannotConnectToHost
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorNotConnectedToInternet
import platform.Foundation.NSURLErrorTimedOut
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ENG-34 (CARDS-BA): the Darwin side of the device-offline classifier — the
 * exact -1009 shape that flooded Sentry from one offline phone must classify
 * as offline, while server-attributable NSURLErrors stay reportable.
 */
class OfflineErrorsTest {

    @Test
    fun notConnectedToInternet_isOffline() {
        assertTrue(darwinError(NSURLErrorNotConnectedToInternet).isOfflineError())
    }

    @Test
    fun cannotConnectToHost_and_timeout_stayReportable() {
        assertFalse(darwinError(NSURLErrorCannotConnectToHost).isOfflineError())
        assertFalse(darwinError(NSURLErrorTimedOut).isOfflineError())
    }

    @Test
    fun nonUrlDomain_isNotOffline() {
        val error = NSError.errorWithDomain("SomeOtherDomain", NSURLErrorNotConnectedToInternet, null)
        assertFalse(DarwinHttpRequestException(error).isOfflineError())
    }

    private fun darwinError(code: Long): Throwable =
        DarwinHttpRequestException(NSError.errorWithDomain(NSURLErrorDomain, code, null))
}
