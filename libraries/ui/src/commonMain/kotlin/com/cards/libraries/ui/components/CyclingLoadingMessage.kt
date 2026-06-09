package com.dangerfield.cards.libraries.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import kotlinx.coroutines.delay
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A reassuring loading caption that only appears once a load is taking a while,
 * then crossfades through a list of [messages] on a fixed cadence.
 *
 * Used to soften a held splash / bootstrap wait: nothing shows for the first
 * [startDelay] (so a fast load never flashes text), after which the first
 * message fades in and the carousel rotates every [interval]. Purely cosmetic —
 * it owns no loading state; the caller hides it when the work completes.
 *
 * Messages cycle in order and wrap; vary [messages] to taste.
 */
@Composable
fun CyclingLoadingMessage(
    messages: List<String>,
    modifier: Modifier = Modifier,
    startDelay: Duration = 5.seconds,
    interval: Duration = 2.5.seconds,
) {
    if (messages.isEmpty()) return

    var index by remember(messages) { mutableStateOf(-1) }

    LaunchedEffect(messages) {
        delay(startDelay)
        index = 0
        while (true) {
            delay(interval)
            index = (index + 1) % messages.size
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = index,
            transitionSpec = {
                fadeIn(tween(400)) togetherWith fadeOut(tween(400))
            },
            label = "CyclingLoadingMessage",
        ) { current ->
            if (current >= 0) {
                Text(
                    text = messages[current],
                    color = AppTheme.colors.contentSecondary,
                    typography = AppTheme.typography.Body.B500,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Poker-flavored default phrases for a held app-bootstrap wait. Provided as a
 * convenience so callers don't reinvent copy; pass your own list to override.
 */
val DefaultBootLoadingMessages: List<String> = listOf(
    "Shuffling the deck",
    "Cutting the cards",
    "Stacking the chips",
    "Dealing you in",
    "Taking your seat",
)

@Preview
@Composable
private fun PreviewCyclingLoadingMessage() {
    PreviewContent {
        // Zero delay so the preview shows the caption immediately.
        CyclingLoadingMessage(
            messages = DefaultBootLoadingMessages,
            startDelay = 0.milliseconds,
        )
    }
}
