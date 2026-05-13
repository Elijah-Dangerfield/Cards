package com.dangerfield.cards.libraries.core.logging

/**
 * A log destination. Trees can decide if they want to record a log and optionally return a
 * [LogId] (for instance the Sentry event id) so callers can pass it further downstream.
 */
abstract class LogTree {
    open fun isLoggable(level: LogLevel, tag: String?): Boolean = true

    abstract fun log(entry: LogEntry): LogId?
}
