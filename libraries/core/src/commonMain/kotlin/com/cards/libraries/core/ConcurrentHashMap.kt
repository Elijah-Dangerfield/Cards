package com.dangerfield.cards.libraries.core

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized

@OptIn(InternalCoroutinesApi::class)
class ConcurrentHashMap<K, V> {
    private val map = mutableMapOf<K, V>()
    private val lock = SynchronizedObject()

    fun put(key: K, value: V): V? = synchronized(lock) {
        map.put(key, value)
    }

    operator fun get(key: K): V? = synchronized(lock) {
        map[key]
    }

    fun remove(key: K): V? = synchronized(lock) {
        map.remove(key)
    }

    fun containsKey(key: K): Boolean = synchronized(lock) {
        map.containsKey(key)
    }

    val size: Int
        get() = synchronized(lock) { map.size }

    operator fun set(key: K, value: V) { put(key, value) }
}

