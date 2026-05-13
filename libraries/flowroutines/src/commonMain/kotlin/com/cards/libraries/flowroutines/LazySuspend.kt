package com.dangerfield.cards.libraries.flowroutines

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Allows a variable to be calculated once in a suspending manner and then reused for future calls
 */
@Suppress("UnusedPrivateMember")
class LazySuspend<T>(private val initializer: suspend () -> T) {
    private var value: T? = null
    private val mutex = Mutex()

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
