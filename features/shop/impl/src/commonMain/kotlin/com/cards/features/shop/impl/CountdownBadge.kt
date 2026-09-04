package com.dangerfield.cards.features.shop.impl

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.products.CatalogTimeAnchor
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.color.defaultColors
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii
import kotlinx.coroutines.delay
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Pill-shaped countdown badge for sale-window offers. Drop it where you'd
 * otherwise put a marketing badge (NEW / POPULAR) — it conveys "ends
 * soon" at a glance.
 *
 * **Resists clock manipulation.** Every tick goes through the
 * [CatalogTimeAnchor] which combines the server's wall clock at fetch
 * with the device's monotonic clock since. Setting the device clock
 * back or forward doesn't change what the badge shows.
 *
 * **Adaptive cadence.** Re-renders once a minute when there are hours
 * left, once a second when under an hour. Saves recompositions for
 * the long-tail "ends in 2 days" case.
 *
 * **Urgency color shifts.** The pill tints get louder as time runs out:
 *  - More than 24h → muted accent (status.okay-tinted)
 *  - 1-24h        → warning amber
 *  - Under 1h     → danger red
 *  - Under 60s    → pulsing danger red
 *
 * **Auto-hide on zero.** Once remaining hits 0, the composable invokes
 * [onExpired] and renders nothing. The caller is responsible for what
 * happens next (typically: refresh the catalog so the now-expired
 * product drops out).
 */
@Composable
internal fun CountdownBadge(
    timeAnchor: CatalogTimeAnchor,
    availableUntilEpochMs: Long,
    onExpired: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var remainingMs by remember(timeAnchor, availableUntilEpochMs) {
        mutableLongStateOf(timeAnchor.remainingMsUntil(availableUntilEpochMs) ?: 0L)
    }

    // Tick on a cadence proportional to urgency. Anything under an hour
    // ticks per second so the seconds digit updates live; longer windows
    // tick per minute so we don't recompose 60× more often than needed.
    LaunchedEffect(timeAnchor, availableUntilEpochMs) {
        while (remainingMs > 0L) {
            val tick = if (remainingMs < 1.hours.inWholeMilliseconds) 1.seconds else 1.minutes
            delay(tick)
            val refreshed = timeAnchor.remainingMsUntil(availableUntilEpochMs) ?: 0L
            remainingMs = refreshed
        }
        onExpired()
    }

    if (remainingMs <= 0L) return

    val urgency = urgencyFor(remainingMs)
    val pulseAlpha = if (urgency == Urgency.Critical) {
        val transition = rememberInfiniteTransition(label = "countdown-pulse")
        val animated = transition.animateFloat(
            initialValue = 0.6f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 600),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "countdown-pulse-alpha",
        )
        animated
    } else {
        rememberUpdatedState(1f)
    }

    Row(
        modifier = modifier
            .graphicsLayer { alpha = pulseAlpha.value }
            .clip(Radii.Round.shape)
            .background(urgency.background.color)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = urgency.icon,
            typography = AppTheme.typography.Label.L400,
            color = urgency.foreground,
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            text = formatCountdown(remainingMs),
            typography = AppTheme.typography.Label.L400,
            color = urgency.foreground,
        )
    }
}

/** Pill-shaped countdown classification — purely about visuals, not
 *  semantic data flow. Keep it private so callers don't reach in. */
private enum class Urgency(
    val icon: String,
    val background: ColorResource,
    val foreground: ColorResource,
) {
    Comfortable(
        icon = "⏱",
        background = defaultColors.surfaceHigh,
        foreground = defaultColors.content,
    ),
    Warning(
        icon = "⏰",
        background = defaultColors.warning,
        foreground = defaultColors.onWarning,
    ),
    Urgent(
        icon = "⏰",
        background = defaultColors.danger,
        foreground = defaultColors.onDanger,
    ),
    Critical(
        icon = "🔥",
        background = defaultColors.danger,
        foreground = defaultColors.onDanger,
    ),
}

private fun urgencyFor(remainingMs: Long): Urgency {
    val oneMin = 1.minutes.inWholeMilliseconds
    val oneHour = oneMin * 60
    val oneDay = oneHour * 24
    return when {
        remainingMs <= oneMin -> Urgency.Critical
        remainingMs <= oneHour -> Urgency.Urgent
        remainingMs <= oneDay -> Urgency.Warning
        else -> Urgency.Comfortable
    }
}

/**
 * Formats a millisecond duration as a compact countdown:
 *
 *  - > 1 day    → `"3d 4h"`
 *  - > 1 hour   → `"4h 12m"`
 *  - > 1 minute → `"12m 34s"`
 *  - else       → `"45s"`
 *
 * Falls off the right side as the window shrinks — readable at a glance
 * on a grid card without needing extra width.
 */
internal fun formatCountdown(remainingMs: Long): String {
    if (remainingMs <= 0L) return "0s"
    val totalSeconds = remainingMs / 1000L
    val days = totalSeconds / 86_400L
    val hours = (totalSeconds % 86_400L) / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        days > 0L -> "${days}d ${hours}h"
        hours > 0L -> "${hours}h ${minutes}m"
        minutes > 0L -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

@Preview
@Composable
private fun CountdownBadgePreview_Comfortable() {
    PreviewContent {
        CountdownBadge(
            timeAnchor = CatalogTimeAnchor.capture(serverNowEpochMs = 0L),
            availableUntilEpochMs = 3L * 86_400_000L,
            onExpired = {},
        )
    }
}

@Preview
@Composable
private fun CountdownBadgePreview_Urgent() {
    PreviewContent {
        CountdownBadge(
            timeAnchor = CatalogTimeAnchor.capture(serverNowEpochMs = 0L),
            availableUntilEpochMs = 45L * 60_000L,
            onExpired = {},
        )
    }
}

@Preview
@Composable
private fun CountdownBadgePreview_Critical() {
    PreviewContent {
        CountdownBadge(
            timeAnchor = CatalogTimeAnchor.capture(serverNowEpochMs = 0L),
            availableUntilEpochMs = 30L * 1_000L,
            onExpired = {},
        )
    }
}
