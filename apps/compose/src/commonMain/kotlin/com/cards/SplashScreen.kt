package com.dangerfield.cards

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.Platform
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.CardsFan
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSize
import com.dangerfield.cards.system.AppTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.tooling.preview.Preview

private const val FadeInMillis = 350
private const val FanOutMillis = 700
private const val FanHoldMillis = 350
private const val FanInMillis = 500
private const val FadeOutMillis = 400

// Minimum on-screen time so a fast `onComplete` doesn't strobe the
// splash. Long enough to land at the fanned position; the visible
// welcome handoff then takes over with cards already fanned.
private const val MinDisplayMillis = FadeInMillis + FanOutMillis + 200

@Composable
fun SplashOverlay(
    onComplete: () -> Unit,
) {
    if (BuildInfo.platform != Platform.iOS) {
        LaunchedEffect(Unit) { onComplete() }
        return
    }

    val alpha = remember { Animatable(0f) }
    val fanProgress = remember { Animatable(0f) }
    var hasReported by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        coroutineScope {
            // Fade in + first fan-out happen together so the cards aren't
            // visually stuck stacked while the screen is still appearing.
            launch { alpha.animateTo(1f, tween(FadeInMillis, easing = LinearEasing)) }
            fanProgress.animateTo(1f, tween(FanOutMillis, easing = EaseInOutCubic))
        }

        // Loop the fan as a loading indicator until enough time has passed.
        // Pulses fan-in → fan-out so it reads as "loading" rather than
        // just sitting fanned.
        var elapsed = FadeInMillis + FanOutMillis
        while (elapsed < MinDisplayMillis) {
            delay(FanHoldMillis.toLong())
            fanProgress.animateTo(0.15f, tween(FanInMillis, easing = EaseInOutCubic))
            fanProgress.animateTo(1f, tween(FanOutMillis, easing = EaseInOutCubic))
            elapsed += FanHoldMillis + FanInMillis + FanOutMillis
        }

        // Land on fully-fanned (matches welcome screen's starting state)
        // and fade the splash out around the cards.
        alpha.animateTo(0f, tween(FadeOutMillis, easing = LinearEasing))
        if (!hasReported) {
            hasReported = true
            onComplete()
        }
    }

    SplashContent(alpha = alpha.value, fanProgress = fanProgress.value)
}

@Composable
private fun SplashContent(alpha: Float, fanProgress: Float) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background.color)
            .graphicsLayer { this.alpha = alpha },
        contentAlignment = Alignment.Center,
    ) {
        CardsFan(
            fanProgress = fanProgress,
            cardSize = PlayingCardSize(width = 88.dp, height = 124.dp),
        )
    }
}

@Preview
@Composable
private fun PreviewSplashOverlay_Fanned() {
    PreviewContent {
        SplashContent(alpha = 1f, fanProgress = 1f)
    }
}

@Preview
@Composable
private fun PreviewSplashOverlay_MidLoop() {
    PreviewContent {
        SplashContent(alpha = 1f, fanProgress = 0.4f)
    }
}
