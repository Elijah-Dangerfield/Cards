package com.dangerfield.cards.libraries.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class RateLimiter(
    private val minTimeBetweenActions: Duration = defaultMinTimeBetweenActions,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {

    private var lastActionTime: TimeMark? = null

    fun canPerformAction(): Boolean =
        lastActionTime?.plus(minTimeBetweenActions)?.hasPassedNow() ?: true

    fun onActionPerformed() {
        lastActionTime = timeSource.markNow()
    }

    inline fun performAction(action: () -> Unit) {
        if (canPerformAction()) {
            onActionPerformed()
            action()
        }
    }

    companion object {
        val defaultMinTimeBetweenActions = 500.milliseconds
    }
}