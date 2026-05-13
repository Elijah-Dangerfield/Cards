package com.dangerfield.cards.libraries.core.logging

/** Public surface for callers. */
interface Logger {
    fun withTag(tag: String?): Logger
    fun setTag(tag: String?): Logger
    fun withScope(configure: ScopeCallback): Logger

    fun v(message: String, throwable: Throwable? = null, scope: ScopeCallback? = null): LogId? =
        log(LogLevel.Verbose, message, throwable, scope)

    fun v(throwable: Throwable, message: String? = null, scope: ScopeCallback? = null): LogId? =
        log(LogLevel.Verbose, throwable, message, scope)

    fun v(messageScope: MessageScope): LogId? =
        log(LogLevel.Verbose, messageScope)

    fun v(throwable: Throwable, messageScope: MessageScope): LogId? =
        log(LogLevel.Verbose, throwable, messageScope)

    fun d(message: String, throwable: Throwable? = null, scope: ScopeCallback? = null): LogId? =
        log(LogLevel.Debug, message, throwable, scope)

    fun d(throwable: Throwable, message: String? = null, scope: ScopeCallback? = null): LogId? =
        log(LogLevel.Debug, throwable, message, scope)

    fun d(messageScope: MessageScope): LogId? =
        log(LogLevel.Debug, messageScope)

    fun d(throwable: Throwable, messageScope: MessageScope): LogId? =
        log(LogLevel.Debug, throwable, messageScope)

    fun i(message: String, throwable: Throwable? = null, scope: ScopeCallback? = null): LogId? =
        log(LogLevel.Info, message, throwable, scope)

    fun i(throwable: Throwable, message: String? = null, scope: ScopeCallback? = null): LogId? =
        log(LogLevel.Info, throwable, message, scope)

    fun i(messageScope: MessageScope): LogId? =
        log(LogLevel.Info, messageScope)

    fun i(throwable: Throwable, messageScope: MessageScope): LogId? =
        log(LogLevel.Info, throwable, messageScope)

    fun w(message: String, throwable: Throwable? = null, scope: ScopeCallback? = null): LogId? =
        log(LogLevel.Warn, message, throwable, scope)

    fun w(throwable: Throwable, message: String? = null, scope: ScopeCallback? = null): LogId? =
        log(LogLevel.Warn, throwable, message, scope)

    fun w(messageScope: MessageScope): LogId? =
        log(LogLevel.Warn, messageScope)

    fun w(throwable: Throwable, messageScope: MessageScope): LogId? =
        log(LogLevel.Warn, throwable, messageScope)

    fun e(message: String, throwable: Throwable? = null, scope: ScopeCallback? = null): LogId? =
        log(LogLevel.Error, message, throwable, scope)

    fun e(throwable: Throwable, message: String? = null, scope: ScopeCallback? = null): LogId? =
        log(LogLevel.Error, throwable, message, scope)

    fun e(messageScope: MessageScope): LogId? =
        log(LogLevel.Error, messageScope)

    fun e(throwable: Throwable, messageScope: MessageScope): LogId? =
        log(LogLevel.Error, throwable, messageScope)

    fun wtf(message: String, throwable: Throwable? = null, scope: ScopeCallback? = null): LogId? =
        log(LogLevel.Assert, message, throwable, scope)

    fun wtf(throwable: Throwable, message: String? = null, scope: ScopeCallback? = null): LogId? =
        log(LogLevel.Assert, throwable, message, scope)

    fun wtf(messageScope: MessageScope): LogId? =
        log(LogLevel.Assert, messageScope)

    fun wtf(throwable: Throwable, messageScope: MessageScope): LogId? =
        log(LogLevel.Assert, throwable, messageScope)

    fun log(
        level: LogLevel,
        message: String,
        throwable: Throwable? = null,
        scope: ScopeCallback? = null
    ): LogId?

    fun log(
        level: LogLevel,
        throwable: Throwable,
        message: String? = null,
        scope: ScopeCallback? = null
    ): LogId?

    fun log(level: LogLevel, scope: MessageScope): LogId?

    fun log(level: LogLevel, throwable: Throwable, scope: MessageScope): LogId?
}

