package com.dangerfield.cards.libraries.flowroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Suppress("UnusedPrivateMember")
class CalculateOnce<T>(
    scope: CoroutineScope,
    private val initializer: suspend () -> T,
) {
    private var value: T? = null
    private val mutex = Mutex()

    init {
        scope.launch {
            getValue()
        }
    }

    suspend fun getValue(): T {
        if (value == null) {
            mutex.withLock {
                if (value == null) { // double check inside lock
                    value = initializer()
                }
            }
        }
        return value!!
    }
}
