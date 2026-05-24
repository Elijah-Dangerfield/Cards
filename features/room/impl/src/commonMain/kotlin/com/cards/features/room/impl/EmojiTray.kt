package com.dangerfield.cards.features.room.impl

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.AppTheme
import kotlinx.coroutines.delay
import kotlin.time.Clock

/**
 * Bottom-tray emoji blast picker. Sits above [QuickActionBar] and
 * collapses to a single circular toggle button when closed so it doesn't
 * eat the action bar's real estate.
 *
 * The cooldown isn't enforced here — the VM owns the cooldown deadline
 * and rejects taps that arrive before [cooldownEndsAtEpochMs]. This
 * composable just renders the visual: dims the emojis and shows a
 * countdown timer over the toggle while cooling. The redundant local
 * check on tap keeps it from firing animation noise into the VM channel
 * during cooldown, but the VM is still authoritative.
 */
@Composable
internal fun EmojiTray(
    emojis: List<String>,
    cooldownEndsAtEpochMs: Long,
    onBlast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    // Tick once a second while cooling so the countdown chip refreshes.
    // Cheap — the inner state only ever flips between the same handful
    // of integers, so most ticks are no-ops at the composition level.
    val now = rememberSecondTicker(active = cooldownEndsAtEpochMs > 0L)
    val cooling = now < cooldownEndsAtEpochMs
    val remainingSeconds = if (cooling) {
        ((cooldownEndsAtEpochMs - now) / 1000L + 1L).coerceAtLeast(1L)
    } else 0L

    // Collapse the tray the moment a cooldown begins — there's nothing
    // useful to tap on, and the dimmed-out picker looks broken.
    LaunchedEffect(cooling) {
        if (cooling) expanded = false
    }

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Dimension.D500),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EmojiToggleButton(
            expanded = expanded,
            cooldownSecondsRemaining = remainingSeconds,
            enabled = !cooling,
            onClick = { expanded = !expanded },
        )

        AnimatedVisibility(
            visible = expanded && !cooling,
            enter = slideInVertically(animationSpec = tween(200)) { it / 2 } +
                fadeIn(animationSpec = tween(200)),
            exit = slideOutVertically(animationSpec = tween(160)) { it / 2 } +
                fadeOut(animationSpec = tween(140)),
        ) {
            EmojiPickerRow(
                emojis = emojis,
                onPick = { emoji ->
                    onBlast(emoji)
                    expanded = false
                },
                modifier = Modifier.padding(start = Dimension.D300),
            )
        }
    }
}

@Composable
private fun EmojiToggleButton(
    expanded: Boolean,
    cooldownSecondsRemaining: Long,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.5f,
        animationSpec = tween(200),
        label = "emoji-toggle-alpha",
    )
    Box(
        modifier = Modifier
            .size(EmojiToggleSize)
            .alpha(alpha)
            .clip(CircleShape)
            .background(AppTheme.colors.surfaceSecondary.color)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (cooldownSecondsRemaining > 0L) {
            Text(
                text = "${cooldownSecondsRemaining}s",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        } else if (expanded) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Close emoji tray",
                tint = AppTheme.colors.text.color,
            )
        } else {
            Icon(
                imageVector = Icons.Default.AddReaction,
                contentDescription = "Send emoji",
                tint = AppTheme.colors.text.color,
            )
        }
    }
}

@Composable
private fun EmojiPickerRow(
    emojis: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier
            .height(EmojiToggleSize)
            .clip(EmojiPickerShape)
            .background(AppTheme.colors.surfaceSecondary.color),
        horizontalArrangement = Arrangement.spacedBy(Dimension.D200),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = Dimension.D400,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(items = emojis, key = { it }) { emoji ->
            EmojiPickerCell(emoji = emoji, onClick = { onPick(emoji) })
        }
    }
}

@Composable
private fun EmojiPickerCell(emoji: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(EmojiCellSize)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = emoji,
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.text,
        )
    }
}

/**
 * Returns wall-clock epoch-ms, refreshed once per second while [active].
 * Inactive while there's no cooldown in flight so we don't keep
 * recomposing the action bar for nothing.
 */
@Composable
private fun rememberSecondTicker(active: Boolean): Long {
    var now by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            now = Clock.System.now().toEpochMilliseconds()
            delay(250L)
        }
    }
    return now
}

private val EmojiToggleSize = 44.dp
private val EmojiCellSize = 40.dp
private val EmojiPickerShape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
