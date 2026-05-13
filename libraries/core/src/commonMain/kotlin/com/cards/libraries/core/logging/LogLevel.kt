package com.dangerfield.cards.libraries.core.logging


/**
 * Describes severity associated with a log event. Matches Android/Sentry ordering so
 * downstream sinks can keep their own filtering logic simple.
 */
enum class LogLevel(val priority: Int) {
    Verbose(2),
    Debug(3),
    Info(4),
    Warn(5),
    Error(6),
    Assert(7),
    Fatal(8),
    ;

    companion object {
        fun fromPriority(priority: Int): LogLevel? = entries.firstOrNull { it.priority == priority }
    }
}
