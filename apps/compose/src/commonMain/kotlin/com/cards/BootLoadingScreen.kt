package com.dangerfield.cards

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.CardsFan
import com.dangerfield.cards.libraries.ui.components.CyclingLoadingMessage
import com.dangerfield.cards.libraries.ui.components.DefaultBootLoadingMessages
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSize
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.jetbrains.compose.ui.tooling.preview.Preview

private const val ShuffleFanInMillis = 500
private const val ShuffleFanOutMillis = 700
private const val ShuffleHoldMillis = 350L

/**
 * The Compose loading gate shown after the platform splash hands off but
 * before the app has finished booting (app-config + profile resolve). It
 * mirrors the splash's fanned-card visual so the handoff from the native /
 * iOS splash is seamless, and surfaces [CyclingLoadingMessage] once the wait
 * runs long (it self-delays, so a fast boot never flashes the caption).
 *
 * The fan keeps pulsing fan-in → fan-out (matching the iOS splash loop) for
 * as long as the gate is up, so a slow boot never reads as a frozen,
 * snapped-open hand. The caption start-delay is short enough that the status
 * text cycles during the wait rather than appearing only after a long hold.
 */
@Composable
fun BootLoadingScreen(
    modifier: Modifier = Modifier,
    messageStartDelay: Duration = 1.5.seconds,
) {
    val fanProgress = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            fanProgress.animateTo(0.2f, tween(ShuffleFanInMillis, easing = EaseInOutCubic))
            fanProgress.animateTo(1f, tween(ShuffleFanOutMillis, easing = EaseInOutCubic))
            delay(ShuffleHoldMillis)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppTheme.colors.background.color),
        contentAlignment = Alignment.Center,
    ) {
        CardsFan(
            fanProgress = fanProgress.value,
            cardSize = PlayingCardSize(width = 88.dp, height = 124.dp),
        )
        CyclingLoadingMessage(
            messages = DefaultBootLoadingMessages,
            startDelay = messageStartDelay,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = Dimension.D900, vertical = Dimension.D1700),
        )
    }
}

@Preview
@Composable
private fun BootLoadingScreenPreview_CaptionShowing() {
    PreviewContent {
        BootLoadingScreen(messageStartDelay = 0.milliseconds)
    }
}
