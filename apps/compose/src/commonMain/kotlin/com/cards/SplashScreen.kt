package com.dangerfield.cards

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.Platform
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import kotlinx.coroutines.delay
import org.jetbrains.compose.ui.tooling.preview.Preview

private const val FadeInMillis = 450
private const val HoldMillis = 650
private const val FadeOutMillis = 450

@Composable
fun SplashOverlay(
    onComplete: () -> Unit,
) {
    if (BuildInfo.platform != Platform.iOS) {
        LaunchedEffect(Unit) { onComplete() }
        return
    }

    val alpha = remember { Animatable(0f) }
    var hasReported by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        alpha.animateTo(1f, tween(FadeInMillis, easing = LinearEasing))
        delay(HoldMillis.toLong())
        alpha.animateTo(0f, tween(FadeOutMillis, easing = LinearEasing))
        if (!hasReported) {
            hasReported = true
            onComplete()
        }
    }

    SplashContent(alpha = alpha.value)
}

@Composable
private fun SplashContent(alpha: Float) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background.color)
            .graphicsLayer { this.alpha = alpha },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = "Cards",
            typography = AppTheme.typography.Brand.B1300,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview
@Composable
private fun PreviewSplashOverlay() {
    PreviewContent {
        SplashContent(alpha = 1f)
    }
}
