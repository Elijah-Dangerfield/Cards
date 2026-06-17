package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalInspectionMode
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.ui_xp_boost_active
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.time.Clock

/**
 * A small teal pill announcing an active **XP Boost** with a live
 * `mm:ss` countdown to expiry — "⚡ XP Boost · 12:30". Self-hides the moment the
 * window lapses, so callers can mount it unconditionally off the boost's
 * `expiresAtEpochMs` and let it disappear on its own.
 *
 * @param expiresAtEpochMs the instant the boost lapses (epoch-ms).
 */
@Composable
fun XpBoostBadge(
    expiresAtEpochMs: Long,
    modifier: Modifier = Modifier,
) {
    val inInspection = LocalInspectionMode.current
    // Pin a deterministic "90s left" in previews; otherwise tick off the wall
    // clock once a second until the window closes.
    var nowEpochMs by remember(expiresAtEpochMs) {
        mutableStateOf(
            if (inInspection) expiresAtEpochMs - 90_000L
            else Clock.System.now().toEpochMilliseconds(),
        )
    }
    LaunchedEffect(expiresAtEpochMs) {
        if (inInspection) return@LaunchedEffect
        while (true) {
            nowEpochMs = Clock.System.now().toEpochMilliseconds()
            if (nowEpochMs >= expiresAtEpochMs) break
            delay(1_000L)
        }
    }

    val remainingMs = expiresAtEpochMs - nowEpochMs
    if (remainingMs <= 0L) return

    val totalSeconds = remainingMs / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val countdown = "$minutes:${seconds.toString().padStart(2, '0')}"

    Row(
        modifier = modifier
            .clip(Radii.Round.shape)
            .background(AppTheme.colors.accentSecondarySubtle.color)
            .padding(horizontal = Dimension.D400, vertical = Dimension.D200),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimension.D200),
    ) {
        Text(text = "⚡", typography = AppTheme.typography.Body.B500)
        Text(
            text = stringResource(Res.string.ui_xp_boost_active, countdown),
            typography = AppTheme.typography.Body.B500.SemiBold,
            color = AppTheme.colors.accentSecondary,
        )
    }
}

@Preview
@Composable
private fun XpBoostBadgePreview() {
    PreviewContent {
        // Inspection mode pins the countdown to 1:30.
        XpBoostBadge(expiresAtEpochMs = 1_000_000L)
    }
}
