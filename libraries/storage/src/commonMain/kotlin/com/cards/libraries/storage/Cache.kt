package com.dangerfield.cards.libraries.storage

interface Cache<T : Any> {
    val updates: kotlinx.coroutines.flow.Flow<T>

    suspend fun get(): T

    suspend fun set(value: T)

    suspend fun clear()

    suspend fun update(transform: (T) -> T): T {
        val newValue = transform(get())
        set(newValue)
        return newValue
    }
}

