package com.dangerfield.cards.features.room.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.Surface
import com.dangerfield.cards.libraries.ui.components.icon.EmojiButton
import com.dangerfield.cards.libraries.ui.components.icon.IconButton
import com.dangerfield.cards.libraries.ui.components.icon.iconSize
import com.dangerfield.cards.libraries.ui.components.icon.padding
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import kotlin.time.Clock
import kotlinx.coroutines.delay
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Compact emoji-blast button suitable for the TopBar. Renders a DS
 * [EmojiButton] (sibling of [IconButton], same Size scale + shape) so
 * the cluster reads as one set of controls. Tap opens a popup containing
 * the [EmojiPickerRow]. Hidden entirely when [emojis] is empty (default
 * users without any `emotes_*` pack), so the chrome stays clean.
 *
 * Cooldown: while [cooldownEndsAtEpochMs] is in the future, the button
 * is replaced by a same-sized non-tappable surface showing remaining
 * seconds. The popup auto-closes the moment a cooldown begins, since
 * the dimmed picker is useless mid-cooldown. The VM is still
 * authoritative — the disabled-on-cooldown gate here just keeps the
 * animation noise out of the channel.
 *
 * The popup's picker cells are intentionally a notch larger than the
 * trigger ([CellSize] > [TriggerSize]) so they're comfortable to tap
 * once the tray is open — the tray no longer inherits its height from
 * the trigger.
 */
@Composable
internal fun TopBarEmojiButton(
    emojis: List<String>,
    cooldownEndsAtEpochMs: Long,
    onBlast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (emojis.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }

    val now = rememberSecondTicker(active = cooldownEndsAtEpochMs > 0L)
    val cooling = now < cooldownEndsAtEpochMs
    val remainingSeconds = if (cooling) {
        ((cooldownEndsAtEpochMs - now) / 1000L + 1L).coerceAtLeast(1L)
    } else 0L

    LaunchedEffect(cooling) {
        if (cooling) expanded = false
    }

    Box(modifier = modifier) {
        if (cooling) {
            CooldownChip(remainingSeconds = remainingSeconds)
        } else {
            EmojiButton(
                emoji = TriggerEmoji,
                size = TriggerSize,
                onClick = { expanded = !expanded },
            )
        }

        if (expanded && !cooling) {
            // Anchored popup directly under the trigger. Offset pushes
            // the picker below using the trigger's own footprint so the
            // gap stays consistent if the Size scale changes. Aligned
            // to TopEnd so the row hugs the right edge — same side as
            // the trigger.
            val triggerFootprint = TriggerSize.iconSize.dp + TriggerSize.padding * 2
            val offsetY = with(LocalDensity.current) {
                (triggerFootprint + Dimension.D200).roundToPx()
            }
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(x = 0, y = offsetY),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                ),
            ) {
                EmojiPickerRow(
                    emojis = emojis,
                    onPick = { emoji ->
                        onBlast(emoji)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun CooldownChip(remainingSeconds: Long) {
    // Same Surface + radius as EmojiButton at TriggerSize, sized to match
    // the icon footprint exactly so the chrome doesn't jump as cooldown
    // toggles.
    Surface(
        color = AppTheme.colors.surfacePrimary,
        contentColor = AppTheme.colors.textSecondary,
        radius = Radii.IconButton,
        contentPadding = PaddingValues(TriggerSize.padding),
    ) {
        Box(
            modifier = Modifier.size(TriggerSize.iconSize.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "${remainingSeconds}s",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
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
    Surface(
        modifier = modifier,
        color = AppTheme.colors.surfaceSecondary,
        contentColor = AppTheme.colors.text,
        radius = Radii.Round,
        contentPadding = PaddingValues(
            horizontal = Dimension.D300,
            vertical = Dimension.D200,
        ),
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Dimension.D100),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(items = emojis, key = { it }) { emoji ->
                EmojiButton(
                    emoji = emoji,
                    size = CellSize,
                    backgroundColor = null,
                    onClick = { onPick(emoji) },
                )
            }
        }
    }
}

/**
 * Wall-clock epoch-ms, refreshed every 250ms while [active] so the
 * countdown chip ticks down smoothly. Inactive otherwise so the
 * TopBar isn't recomposing for nothing.
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

private val TriggerSize = IconButton.Size.Medium
private val CellSize = IconButton.Size.Large
private const val TriggerEmoji = "😀"

private val PreviewEmojis = listOf("🔥", "😂", "👀", "💀", "🎉", "🤝")

@Preview
@Composable
private fun TopBarEmojiButtonPreview_Idle() {
    PreviewContent {
        TopBarEmojiButton(
            emojis = PreviewEmojis,
            cooldownEndsAtEpochMs = 0L,
            onBlast = {},
        )
    }
}

@Preview
@Composable
private fun TopBarEmojiButtonPreview_Cooldown() {
    PreviewContent {
        TopBarEmojiButton(
            emojis = PreviewEmojis,
            cooldownEndsAtEpochMs = Clock.System.now().toEpochMilliseconds() + 5_000L,
            onBlast = {},
        )
    }
}

@Preview
@Composable
private fun EmojiPickerRowPreview() {
    PreviewContent {
        EmojiPickerRow(emojis = PreviewEmojis, onPick = {})
    }
}
