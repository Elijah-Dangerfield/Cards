package com.dangerfield.cards.libraries.core.logging

data class LogContext(
    val tags: Map<String, String>,
    val extras: Map<String, Any?>
) {
    fun isEmpty(): Boolean = tags.isEmpty() && extras.isEmpty()

    fun merge(other: LogContext): LogContext {
        if (other.isEmpty()) return this
        if (isEmpty()) return other

        val mergedTags = tags.toMutableMap().apply { putAll(other.tags) }
        val mergedExtras = extras.toMutableMap().apply { putAll(other.extras) }
        return LogContext(mergedTags.toMap(), mergedExtras.toMap())
    }

    companion object {
        val Empty = LogContext(emptyMap(), emptyMap())
    }
}

typealias ScopeCallback = (Scope) -> Unit
fun interface MessageScope {
    fun produce(scope: Scope): String?
}
internal val EmptyScope: ScopeCallback = {}

internal data class ScopedMessage(
    val context: LogContext,
    val message: String?
)

/** Public contract for mutating scope data inside callbacks. */
interface Scope {
    fun tag(key: String, value: String?)

    fun extra(key: String, value: String?)
    fun extra(key: String, value: Int?)
    fun extra(key: String, value: Long?)
    fun extra(key: String, value: Float?)
    fun extra(key: String, value: Double?)
    fun extra(key: String, value: Boolean?)

    fun removeTag(key: String) = tag(key, null)
    fun removeExtra(key: String) = extra(key, null as String?)
}

internal class MutableScope(initial: LogContext = LogContext.Empty) : Scope {
    private val tags = initial.tags.toMutableMap()
    private val extras = initial.extras.toMutableMap()

    override fun tag(key: String, value: String?) {
        if (value == null) tags.remove(key) else tags[key] = value
    }

    override fun extra(key: String, value: String?) = setExtra(key, value)
    override fun extra(key: String, value: Int?) = setExtra(key, value)
    override fun extra(key: String, value: Long?) = setExtra(key, value)
    override fun extra(key: String, value: Float?) = setExtra(key, value)
    override fun extra(key: String, value: Double?) = setExtra(key, value)
    override fun extra(key: String, value: Boolean?) = setExtra(key, value)

    override fun removeExtra(key: String) {
        extras.remove(key)
    }

    fun snapshot(): LogContext = if (tags.isEmpty() && extras.isEmpty()) {
        LogContext.Empty
    } else {
        LogContext(tags.toMap(), extras.toMap())
    }

    private fun setExtra(key: String, value: Any?) {
        if (value == null) extras.remove(key) else extras[key] = value
    }
}

internal fun extendContext(
    base: LogContext,
    callback: ScopeCallback
): LogContext {
    if (callback === EmptyScope) return base
    val scope = MutableScope(base)
    callback(scope)
    return scope.snapshot()
}

internal fun applyScope(base: LogContext, callback: ScopeCallback?): LogContext = when {
    callback == null -> base
    callback === EmptyScope -> base
    else -> extendContext(base, callback)
}

internal fun applyScopeWithMessage(
    base: LogContext,
    callback: MessageScope?
): ScopedMessage {
    if (callback == null) return ScopedMessage(base, null)
    val scope = MutableScope(base)
    val message = callback.produce(scope)
    return ScopedMessage(scope.snapshot(), message)
}
