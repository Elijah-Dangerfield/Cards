package com.dangerfield.cards.libraries.networking

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException

internal actual fun isPlatformOfflineError(throwable: Throwable): Boolean = when (throwable) {
    is UnknownHostException, is NoRouteToHostException -> true
    // "Connection refused" stays reportable — that's the backend's port, not
    // the device's radio. Only the no-route flavors are offline-class.
    is ConnectException, is SocketException ->
        throwable.message?.contains("unreachable", ignoreCase = true) == true
    else -> false
}
