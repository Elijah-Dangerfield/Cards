package com.dangerfield.cards.libraries.core.logging

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/** Global logger entry point. */
@OptIn(ExperimentalObjCName::class)
@ObjCName("KLog", exact = true)
object KLog : Logger {
    override fun withTag(tag: String?): Logger = ScopedLogger(baseTag = tag)

    override fun setTag(tag: String?): Logger = ScopedLogger().setTag(tag)

    override fun withScope(configure: ScopeCallback): Logger = ScopedLogger().withScope(configure)

    override fun log(
        level: LogLevel,
        message: String,
        throwable: Throwable?,
        scope: ScopeCallback?
    ): LogId? = ScopedLogger().log(level, message, throwable, scope)

    override fun log(
        level: LogLevel,
        throwable: Throwable,
        message: String?,
        scope: ScopeCallback?
    ): LogId? = ScopedLogger().log(level, throwable, message, scope)

    override fun log(level: LogLevel, scope: MessageScope): LogId? =
        ScopedLogger().log(level, scope)

    override fun log(level: LogLevel, throwable: Throwable, scope: MessageScope): LogId? =
        ScopedLogger().log(level, throwable, scope)

    fun scoped(): Logger = ScopedLogger()

    fun plant(tree: LogTree) = LoggingEngine.plant(tree)

    fun uproot(tree: LogTree) = LoggingEngine.uproot(tree)

    fun plantedTrees(): List<LogTree> = LoggingEngine.snapshot()

    fun clearTrees() = LoggingEngine.clear()

    fun configureScope(block: ScopeCallback?) = LoggingEngine.configureScope(block)

    fun currentScope(): LogContext = LoggingEngine.currentScope()

    fun configureAutoTagging(
        enabled: Boolean,
        maxTagLength: Int = DEFAULT_TAG_LENGTH,
        ignorePackages: Set<String> = emptySet()
    ) = LoggingEngine.configureAutoTagging(enabled, maxTagLength, ignorePackages)
}

private class ScopedLogger(
    private val baseTag: String? = null,
    private val baseContext: LogContext = LogContext.Empty
) : Logger {

    private var transientTag: String? = null

    override fun withTag(tag: String?): Logger {
        if (tag == baseTag) return this
        return ScopedLogger(tag, baseContext)
    }

    override fun setTag(tag: String?): Logger {
        transientTag = tag
        return this
    }

    override fun withScope(configure: ScopeCallback): Logger {
        if (configure === EmptyScope) return this
        val updated = extendContext(baseContext, configure)
        return if (updated == baseContext) this else ScopedLogger(baseTag, updated)
    }

    override fun log(
        level: LogLevel,
        message: String,
        throwable: Throwable?,
        scope: ScopeCallback?
    ): LogId? = submit(level, throwable = throwable, messageOverride = message, scope = scope, messageScope = null)

    override fun log(
        level: LogLevel,
        throwable: Throwable,
        message: String?,
        scope: ScopeCallback?
    ): LogId? = submit(level, throwable = throwable, messageOverride = message, scope = scope, messageScope = null)

    override fun log(level: LogLevel, scope: MessageScope): LogId? =
        submit(level, throwable = null, messageOverride = null, scope = null, messageScope = scope)

    override fun log(level: LogLevel, throwable: Throwable, scope: MessageScope): LogId? =
        submit(level, throwable = throwable, messageOverride = null, scope = null, messageScope = scope)

    private fun submit(
        level: LogLevel,
        throwable: Throwable?,
        messageOverride: String?,
        scope: ScopeCallback?,
        messageScope: MessageScope?
    ): LogId? {
        val explicitTag = transientTag ?: baseTag
        transientTag = null

        val (context, scopedMessage) = if (messageScope != null) {
            val result = applyScopeWithMessage(baseContext, messageScope)
            result.context to result.message
        } else {
            applyScope(baseContext, scope) to null
        }

        val resolvedMessage = messageOverride ?: scopedMessage ?: throwable?.message

        return LoggingEngine.submit(
            level = level,
            throwable = throwable,
            message = resolvedMessage,
            explicitTag = explicitTag,
            context = context
        )
    }
}

@OptIn(InternalCoroutinesApi::class)
private object LoggingEngine {
    private val treeLock = SynchronizedObject()
    private val trees = mutableListOf<LogTree>()
    private var treeSnapshot: Array<LogTree> = emptyArray()

    private val scopeLock = SynchronizedObject()
    private var globalScope: LogContext = LogContext.Empty

    private var tagResolver: TagResolver = TagResolver.NoOp

    fun plant(tree: LogTree) {
        synchronized(treeLock) {
            trees.add(tree)
            treeSnapshot = trees.toTypedArray()
        }
    }

    fun uproot(tree: LogTree) {
        synchronized(treeLock) {
            if (trees.remove(tree)) {
                treeSnapshot = trees.toTypedArray()
            }
        }
    }

    fun clear() {
        synchronized(treeLock) {
            trees.clear()
            treeSnapshot = emptyArray()
        }
    }

    fun snapshot(): List<LogTree> = treeSnapshot.toList()

    fun configureScope(block: ScopeCallback?) {
        if (block == null || block === EmptyScope) return
        synchronized(scopeLock) {
            globalScope = extendContext(globalScope, block)
        }
    }

    fun currentScope(): LogContext = globalScope

    fun configureAutoTagging(enabled: Boolean, maxTagLength: Int, ignorePackages: Set<String>) {
        tagResolver = if (enabled) {
            TagResolver.callSite(maxTagLength, ignorePackages)
        } else {
            TagResolver.NoOp
        }
    }

    fun submit(
        level: LogLevel,
        throwable: Throwable?,
        message: String?,
        explicitTag: String?,
        context: LogContext
    ): LogId? {
        if (throwable == null && message.isNullOrBlank()) return null

        val combinedContext = globalScope.merge(context)
        val finalTag = explicitTag ?: tagResolver.resolve()

        var capturedId: LogId? = null
        val snapshot = treeSnapshot
        for (tree in snapshot) {
            if (!tree.isLoggable(level, finalTag)) continue
            val id = tree.log(
                LogEntry(
                    level = level,
                    tag = finalTag,
                    message = message,
                    throwable = throwable,
                    context = combinedContext
                )
            )
            if (capturedId == null && id != null) {
                capturedId = id
            }
        }
        return capturedId
    }
}

private interface TagResolver {
    fun resolve(): String?

    object NoOp : TagResolver {
        override fun resolve(): String? = null
    }

    companion object {
        fun callSite(maxLength: Int, ignorePackages: Set<String>): TagResolver {
            val ignored = DEFAULT_IGNORED + ignorePackages
            return CallSiteTagResolver(maxLength, ignored)
        }
    }
}

private class CallSiteTagResolver(
    private val maxLength: Int,
    private val ignorePackages: Set<String>
) : TagResolver {
    private val anonymousRegex = Regex("(\\$\\d+)+\\z")
    private val stackLineRegex = Regex("\\s*at\\s+([\\w.$]+)")

    override fun resolve(): String? {
        val stack = Throwable().stackTraceToString()
        val frameClass = stackLineRegex.findAll(stack)
            .map { it.groupValues[1] }
            .firstOrNull { className ->
                ignorePackages.none { ignore -> ignore.isNotEmpty() && className.startsWith(ignore) }
            } ?: return null

        var tag = frameClass.substringAfterLast('.')
        tag = anonymousRegex.replace(tag, "")
        return if (tag.length <= maxLength) tag else tag.substring(0, maxLength)
    }
}

private val DEFAULT_TAG_LENGTH = 25

private val DEFAULT_IGNORED = listOfNotNull(
    ScopedLogger::class.qualifiedName,
    KLog::class.qualifiedName,
    LoggingEngine::class.qualifiedName
).toSet()
