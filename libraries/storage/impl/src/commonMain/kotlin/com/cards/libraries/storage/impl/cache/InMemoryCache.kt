package com.dangerfield.cards.libraries.storage.impl.cache

import com.dangerfield.cards.libraries.storage.Cache
import kotlinx.coroutines.flow.MutableStateFlow

class InMemoryCache<T : Any>(
    private val initial: () -> T,
) : Cache<T> {
    private val state = MutableStateFlow(initial())

    override val updates = state

    override suspend fun get(): T = state.value

    override suspend fun set(value: T) { state.value = value }

    override suspend fun clear() {
        state.value = initial()
    }
}
