package com.dangerfield.cards.libraries.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.dangerfield.cards.libraries.core.RateLimiter


/**
 * Wraps the given [callback] lambda with a [RateLimiter] that will limit the actions performed in a short period of time.
 *
 * If multiple callbacks happen in a short period of time, only the first callback will be performed.
 */

@Composable
fun throttledCallback(callback: () -> Unit): () -> Unit =
    remember { ThrottledCallback(callback) }
        .also { it.delegate = callback }

private class ThrottledCallback(var delegate: () -> Unit) : () -> Unit {
    private val throttler = RateLimiter()

    override operator fun invoke() = throttler.performAction(delegate)
}

