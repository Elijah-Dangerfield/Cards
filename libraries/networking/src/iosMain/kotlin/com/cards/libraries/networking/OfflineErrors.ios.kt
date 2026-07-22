package com.dangerfield.cards.libraries.networking

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import platform.Foundation.NSURLErrorCannotFindHost
import platform.Foundation.NSURLErrorDNSLookupFailed
import platform.Foundation.NSURLErrorDataNotAllowed
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorInternationalRoamingOff
import platform.Foundation.NSURLErrorNetworkConnectionLost
import platform.Foundation.NSURLErrorNotConnectedToInternet

internal actual fun isPlatformOfflineError(throwable: Throwable): Boolean {
    val origin = (throwable as? DarwinHttpRequestException)?.origin ?: return false
    return origin.domain == NSURLErrorDomain && origin.code in OFFLINE_URL_ERROR_CODES
}

// The NSURLError family meaning "this device has no usable route": offline,
// the connection dropping mid-flight, cellular data disabled, roaming off, or
// DNS unable to resolve anything. Cannot-connect (-1004) and timeouts stay
// reportable — the server being down is exactly what we want to hear about.
private val OFFLINE_URL_ERROR_CODES = setOf(
    NSURLErrorNotConnectedToInternet,
    NSURLErrorNetworkConnectionLost,
    NSURLErrorDataNotAllowed,
    NSURLErrorInternationalRoamingOff,
    NSURLErrorDNSLookupFailed,
    NSURLErrorCannotFindHost,
)
