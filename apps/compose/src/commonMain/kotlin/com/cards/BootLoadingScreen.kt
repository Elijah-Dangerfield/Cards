package com.dangerfield.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * The Compose loading gate shown after the platform splash hands off but
 * before the app has finished booting (app-config + profile resolve). It
 * mirrors the splash's fanned-card visual so the handoff from the native /
 * iOS splash is seamless, and surfaces [CyclingLoadingMessage] once the wait
 * runs long (it self-delays, so a fast boot never flashes the caption).
 */
@Composable
fun BootLoadingScreen(
    modifier: Modifier = Modifier,
    messageStartDelay: Duration = 5.seconds,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppTheme.colors.background.color),
        contentAlignment = Alignment.Center,
    ) {
        CardsFan(
            fanProgress = 1f,
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
