package com.dangerfield.cards.libraries.core.logging

import kotlin.experimental.ExperimentalObjCName
import kotlin.jvm.JvmInline
import kotlin.native.ObjCName

/**
 * Immutable snapshot of a log event delivered to planted log trees.
 */
data class LogEntry(
    val level: LogLevel,
    val tag: String?,
    val message: String?,
    val throwable: Throwable?,
    val context: LogContext
)

/**
 * Represents an optional identifier produced by one of the planted log trees (for example a Sentry
 * event id). We keep it as a simple inline value so it can cross multiplatform boundaries easily.
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName("LogId", exact = true)
@JvmInline
value class LogId(val raw: String) {
    override fun toString(): String = raw

    companion object {
        fun from(value: String?): LogId? = value?.takeIf { it.isNotBlank() }?.let(::LogId)
    }
}