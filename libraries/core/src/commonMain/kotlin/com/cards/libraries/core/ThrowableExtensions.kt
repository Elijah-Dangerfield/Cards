package com.dangerfield.cards.libraries.core

import com.dangerfield.cards.libraries.core.logging.KLog
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.cancellation.CancellationException


val Throwable.shouldNotBeCaught: Boolean
    get() = when {
        isThrowableCancellation()
//                || this is VirtualMachineError
//                || this is ThreadDeath
//                || this is InterruptedException
//                || this is LinkageError
                     -> true
        else -> false
    }

private fun Throwable.isThrowableCancellation() =
    this is CancellationException && this !is TimeoutCancellationException

/**
 * Marker for typed, expected control-flow throwables — a short-circuit the caller
 * is meant to handle, not a failure. Telemetry sinks drop these before they turn
 * into error events, so an expected signal that reaches an error-level log line
 * never inflates error counts. [AuthUnready] is the reference implementor.
 */
interface ExpectedControlFlow

val Throwable.isExpectedControlFlow: Boolean
    get() = this is ExpectedControlFlow

class DebugException(e: Throwable? = null, message: String? = e?.message) :
    Exception(message, e)

fun throwIfDebug(throwable: Throwable) {
    if (BuildInfo.isDebug) {
        throw DebugException(message = throwable.message.orEmpty())
    }
}

fun throwIfDebug(lazyMessage: () -> Any) {
    if (BuildInfo.isDebug) {
        throw DebugException(message = lazyMessage().toString())
    }
    KLog.e(lazyMessage().toString())
}

inline fun checkInDebug(value: Boolean, lazyMessage: () -> Any) {
    if (!value) {
        if (BuildInfo.isDebug) throw DebugException(message = lazyMessage().toString())
    }
}