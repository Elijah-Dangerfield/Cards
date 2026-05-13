package com.dangerfield.cards.libraries.cards.impl.logging

import com.dangerfield.cards.libraries.core.logging.LogContext
import com.dangerfield.cards.libraries.core.logging.LogEntry
import com.dangerfield.cards.libraries.core.logging.LogId
import com.dangerfield.cards.libraries.core.logging.LogLevel
import com.dangerfield.cards.libraries.core.logging.LogTree

/**
 * Forwards structured log entries to the existing Kermit logger so new callers using [com.dangerfield.cards.libraries.core.logging.KLog]
 * continue to land in the same log sinks (Logcat, Xcode, etc.).
 */
class KermitLogTree(
    private val minLevel: LogLevel = LogLevel.Verbose,
) : LogTree() {

    override fun isLoggable(level: LogLevel, tag: String?): Boolean {
        return level.priority >= minLevel.priority
    }

    override fun log(entry: LogEntry): LogId? {
        val logger = co.touchlab.kermit.Logger
        val resolvedTag = entry.tag ?: logger.tag

        val messageBlock = { formatMessage(entry) }

        when (entry.level) {
            LogLevel.Verbose -> logger.withTag(resolvedTag).v(throwable = entry.throwable, message = messageBlock)
            LogLevel.Debug -> logger.withTag(resolvedTag).d(throwable = entry.throwable, message = messageBlock)
            LogLevel.Info -> logger.withTag(resolvedTag).i(throwable = entry.throwable, message = messageBlock)
            LogLevel.Warn -> logger.withTag(resolvedTag).w(throwable = entry.throwable, message = messageBlock)
            LogLevel.Error -> logger.withTag(resolvedTag).e(throwable = entry.throwable, message = messageBlock)
            LogLevel.Assert -> logger.withTag(resolvedTag).a(throwable = entry.throwable, message = messageBlock)
            LogLevel.Fatal -> logger.withTag(resolvedTag).a(throwable = entry.throwable, message = messageBlock)
        }
        return null
    }

    private fun formatMessage(entry: LogEntry): String {
        val baseMessage = entry.message?.takeUnless { it.isBlank() }
            ?: entry.throwable?.message
            ?: "<no-message>"

        val contextDescription = describeContext(entry.context)
        return if (contextDescription != null) {
            "$baseMessage | $contextDescription"
        } else {
            baseMessage
        }
    }

    private fun describeContext(context: LogContext): String? {
        if (context.isEmpty()) return null

        val parts = buildList {
            if (context.tags.isNotEmpty()) {
                add("tags=${context.tags}")
            }
            if (context.extras.isNotEmpty()) {
                add("extras=${context.extras}")
            }
        }

        return parts.joinToString(", ")
    }
}