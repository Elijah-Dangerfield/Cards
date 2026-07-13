package com.dangerfield.cards.libraries.cards.impl.logging

import com.dangerfield.cards.libraries.core.AuthReason
import com.dangerfield.cards.libraries.core.AuthUnready
import com.dangerfield.cards.libraries.core.logging.LogContext
import com.dangerfield.cards.libraries.core.logging.LogEntry
import com.dangerfield.cards.libraries.core.logging.LogLevel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SentryLogTreeTest {

    private val tree = SentryLogTree(
        minBreadcrumbLevel = LogLevel.Info,
        minEventLevel = LogLevel.Error,
    )

    @Test
    fun `expected control-flow throwable at error level is not captured as an event`() {
        assertFalse(tree.shouldCaptureEvent(errorEntry(AuthUnready(AuthReason.FinishingSetup))))
        assertFalse(tree.shouldCaptureEvent(errorEntry(AuthUnready(AuthReason.NeedAccount))))
    }

    @Test
    fun `real throwable at error level is captured as an event`() {
        assertTrue(tree.shouldCaptureEvent(errorEntry(IllegalStateException("boom"))))
    }

    @Test
    fun `error-level message without a throwable is captured as an event`() {
        assertTrue(tree.shouldCaptureEvent(errorEntry(throwable = null)))
    }

    @Test
    fun `below-threshold entries are never captured as events`() {
        assertFalse(
            tree.shouldCaptureEvent(
                LogEntry(
                    level = LogLevel.Warn,
                    tag = "test",
                    message = "warn",
                    throwable = IllegalStateException("boom"),
                    context = LogContext.Empty,
                )
            )
        )
    }

    private fun errorEntry(throwable: Throwable?): LogEntry = LogEntry(
        level = LogLevel.Error,
        tag = "test",
        message = throwable?.message ?: "error",
        throwable = throwable,
        context = LogContext.Empty,
    )
}
