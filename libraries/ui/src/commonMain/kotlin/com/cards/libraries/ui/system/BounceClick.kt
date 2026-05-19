package com.dangerfield.cards.libraries.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Indication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Animated, debounced click handler used by Button, IconButton, and the
 * tappable list items.
 *
 * **Debounce is on by default** ([DefaultDebounce] = 400 ms) so a user
 * double-tapping a row can't end up with two screens on the back stack
 * or two purchases on a rate-limited endpoint. The window is short
 * enough that a deliberate "tap twice quickly" interaction still
 * registers the first one; the second is silently dropped.
 *
 * Set [debounce] to [Duration.ZERO] to opt out — useful for things like
 * a stepper button where rapid repeats are the actual intent.
 *
 * The clock is monotonic (not wall-clock) so changing the device time
 * mid-interaction can't suddenly re-enable a click.
 */
fun Modifier.bounceClick(
    enabled: Boolean = true,
    mutableInteractionSource: MutableInteractionSource? = null,
    indication: Indication? = null,
    scaleDown: Float = 0.90f,
    debounce: Duration = DefaultDebounce,
    onClick: () -> Unit = {}
) = composed {

    val interactionSource = mutableInteractionSource ?: remember { MutableInteractionSource() }

    val animatable = remember { Animatable(1f) }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> animatable.animateTo(scaleDown)
                is PressInteraction.Release -> animatable.animateTo(1f)
                is PressInteraction.Cancel -> animatable.animateTo(1f)
            }
        }
    }

    val lastClickMark = remember { mutableStateOf<TimeMark?>(null) }
    val debouncedOnClick: () -> Unit = {
        val previous = lastClickMark.value
        if (debounce <= Duration.ZERO || previous == null || previous.elapsedNow() >= debounce) {
            lastClickMark.value = TimeSource.Monotonic.markNow()
            onClick()
        }
    }

    this
        .graphicsLayer {
            val scale = animatable.value
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = indication,
            onClick = debouncedOnClick,
        )
}

/** Default debounce window. Tuned to absorb accidental double-taps without feeling laggy. */
val DefaultDebounce: Duration = 400.milliseconds
