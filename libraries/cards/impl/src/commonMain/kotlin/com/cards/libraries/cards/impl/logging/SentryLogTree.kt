package com.dangerfield.cards.libraries.cards.impl.logging

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.LogContext
import com.dangerfield.cards.libraries.core.logging.LogEntry
import com.dangerfield.cards.libraries.core.logging.LogId
import com.dangerfield.cards.libraries.core.logging.LogLevel
import com.dangerfield.cards.libraries.core.logging.LogTree
import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.SentryLevel
import io.sentry.kotlin.multiplatform.Scope
import io.sentry.kotlin.multiplatform.protocol.Breadcrumb

class SentryLogTree(
    private val minBreadcrumbLevel: LogLevel,
    private val minEventLevel: LogLevel
) : LogTree() {

    override fun isLoggable(level: LogLevel, tag: String?): Boolean {
        if (!Sentry.isEnabled()) return false
        return level.priority >= minBreadcrumbLevel.priority || level.priority >= minEventLevel.priority
    }

    override fun log(entry: LogEntry): LogId? {
        if (!Sentry.isEnabled()) return null

        if (entry.level.priority >= minBreadcrumbLevel.priority) {
            addBreadcrumb(entry)
        }

        if (entry.level.priority >= minEventLevel.priority) {
            return captureEvent(entry)
        }

        return null
    }

    private fun addBreadcrumb(entry: LogEntry) {
        val breadcrumb = Breadcrumb().apply {
            level = entry.level.toSentryLevel()
            category = entry.tag ?: BREADCRUMB_CATEGORY
            message = entry.message ?: entry.throwable?.message ?: DEFAULT_MESSAGE
        }

        entry.context.tags.forEach { (key, value) ->
            breadcrumb.setData("tag.$key", value)
        }
        entry.context.extras.forEach { (key, value) ->
            if (value != null) {
                breadcrumb.setData("extra.$key", value)
            }
        }

        Sentry.addBreadcrumb(breadcrumb)
    }

    private fun captureEvent(entry: LogEntry): LogId? {
        val message = entry.message ?: entry.throwable?.message ?: DEFAULT_MESSAGE
        val sentryLevel = entry.level.toSentryLevel()

        val sentryId = entry.throwable?.let {
            Sentry.captureException(it) { scope ->
                scope.level = sentryLevel
                applyContext(scope, entry)
            }
        } ?: Sentry.captureMessage(message) { scope ->
            scope.level = sentryLevel
            applyContext(scope, entry)
        }

        return LogId.from(sentryId.toString())
    }

    private fun applyContext(scope: Scope, entry: LogEntry) {
        val tag = entry.tag
        if (!tag.isNullOrBlank()) {
            scope.setTag(LOGGER_TAG_KEY, tag)
        }

        applyContextMaps(scope, entry.context)
    }

    private fun applyContextMaps(scope: Scope, context: LogContext) {
        context.tags.forEach { (key, value) ->
            scope.setTag(key, value)
        }
        context.extras.forEach { (key, value) ->
            if (value != null) {
                scope.setExtra(key, Catching {value.toString()}.getOrElse { "${value::class.simpleName}" } )
            }
        }
    }

    private fun LogLevel.toSentryLevel(): SentryLevel = when (this) {
        LogLevel.Verbose, LogLevel.Debug -> SentryLevel.DEBUG
        LogLevel.Info -> SentryLevel.INFO
        LogLevel.Warn -> SentryLevel.WARNING
        LogLevel.Error -> SentryLevel.ERROR
        LogLevel.Assert, LogLevel.Fatal -> SentryLevel.FATAL
    }

    companion object {
        private const val LOGGER_TAG_KEY = "logger_tag"
        private const val BREADCRUMB_CATEGORY = "klog"
        private const val DEFAULT_MESSAGE = "(no message)"
    }
}