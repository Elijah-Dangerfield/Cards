package com.dangerfield.cards.admin

import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Local copy of :libraries:core's `Catching` — that module has no `js()`
 * target and this tool deliberately shares no client code. Same contract:
 * rethrows [CancellationException] so a cancelled coroutine stays cancelled
 * (plain `runCatching` swallows it), while [TimeoutCancellationException]
 * still lands as a failure.
 */
internal typealias Catching<T> = Result<T>

internal inline fun <T> Catching(block: () -> T): Catching<T> = runCatching(block)
    .onFailure {
        if (it is CancellationException && it !is TimeoutCancellationException) throw it
    }
