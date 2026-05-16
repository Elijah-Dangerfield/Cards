package com.dangerfield.cards.libraries.navigation

import com.dangerfield.cards.libraries.core.Catching
import kotlin.reflect.KClass

private data class ErrorCodeMapping(
    val code: Int,
    val predicate: (Throwable) -> Boolean
) {
    fun matches(throwable: Throwable): Boolean = Catching { predicate(throwable) }.getOrDefault(false)
}

/**
 * Simple registry for mapping [Throwable] instances to readable numeric codes that can be surfaced
 * in UI elements. Extend this by calling [KnownErrorCodes.register] from feature-specific modules
 * if you need additional mappings.
 */
object KnownErrorCodes {
    private val mappings = mutableListOf<ErrorCodeMapping>()

    init {
        register(code = 1001, klass = IllegalArgumentException::class)
        register(code = 1002, klass = IllegalStateException::class)
        register(code = 1003, klass = NullPointerException::class)
    }

    fun register(code: Int, klass: KClass<out Throwable>) {
        register(code) { klass.isInstance(it) }
    }

    fun register(code: Int, predicate: (Throwable) -> Boolean) {
        mappings += ErrorCodeMapping(code, predicate)
    }

    internal fun resolve(throwable: Throwable): Int? {
        for (candidate in generateSequence(throwable) { it.cause }) {
            val match = mappings.firstOrNull { it.matches(candidate) }
            if (match != null) return match.code
        }
        return null
    }
}

fun Throwable?.toKnownErrorCode(): Int? {
    val error = this ?: return null
    return KnownErrorCodes.resolve(error)
}
