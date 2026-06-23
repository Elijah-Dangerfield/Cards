package com.dangerfield.cards.features.room.impl.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.room_emoji_tray_cooldown
import cards.libraries.resources.generated.resources.room_emoji_tray_empty_caption
import com.dangerfield.cards.libraries.cards.EmotePackCatalog
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.Surface
import com.dangerfield.cards.libraries.ui.components.icon.EmojiButton
import com.dangerfield.cards.libraries.ui.components.icon.IconButton
import com.dangerfield.cards.libraries.ui.components.icon.iconSize
import com.dangerfield.cards.libraries.ui.components.icon.padding
import com.dangerfield.cards.libraries.ui.components.poker.LocalFeltAccentSurface
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD300
import kotlin.time.Clock
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Compact emoji-blast button suitable for the TopBar. Renders a DS
 * [EmojiButton] (sibling of [IconButton], same Size scale + shape) so
 * the cluster reads as one set of controls. Tap opens a popup containing
 * either [EmojiPickerRow] (user owns ≥1 `emotes_*` pack) or
 * [EmptyEmojiPopup] (user owns none; greyed-out preview + caption).
 * The trigger always renders so the affordance is visible — receiving
 * emotes still works without ownership, and the empty-state popup
 * surfaces the path to start sending them.
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
    val ownsAnyPack = emojis.isNotEmpty()
    var expanded by remember { mutableStateOf(false) }

    val now = rememberSecondTicker(active = cooldownEndsAtEpochMs > 0L)
    val cooling = now < cooldownEndsAtEpochMs
    val remainingSeconds = if (cooling) {
        ((cooldownEndsAtEpochMs - now) / 1000L + 1L).coerceAtLeast(1L)
    } else 0L

    LaunchedEffect(cooling) {
        if (cooling) expanded = false
    }

    val triggerFootprint = TriggerSize.iconSize.dp + TriggerSize.padding * 2

    Box(modifier = modifier.size(triggerFootprint)) {
        if (cooling) {
            CooldownChip(remainingSeconds = remainingSeconds)
        } else {
            EmojiButton(
                emoji = TriggerEmoji,
                size = TriggerSize,
                onClick = { expanded = !expanded },
            )
        }

        // Drive enter/exit through a MutableTransitionState so the
        // Popup itself stays mounted through the exit animation —
        // wrapping `Popup` in a plain `if (expanded)` would yank the
        // popup out the instant the user dismisses, skipping the
        // shrink/fade. Mount-while-(currentState || targetState) is
        // the canonical pattern for animated popups in Compose.
        val visibleState = remember { MutableTransitionState(initialState = false) }
        visibleState.targetState = expanded && !cooling

        if (visibleState.currentState || visibleState.targetState) {
            // Anchored popup directly under the trigger. Offset pushes
            // the picker below using the trigger's own footprint so the
            // gap stays consistent if the Size scale changes. Aligned
            // to TopEnd so the row hugs the right edge — same side as
            // the trigger.
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
                // Scale + fade from the trigger's pivot (top-right of
                // the tray, since trigger sits at TopEnd of the bar).
                // The pivot makes the tray feel like it physically
                // unfurls from the button rather than dropping in as a
                // detached panel. Exit is a touch faster than enter so
                // dismiss feels snappy without looking abrupt.
                AnimatedVisibility(
                    visibleState = visibleState,
                    enter = scaleIn(
                        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                        initialScale = 0.82f,
                        transformOrigin = TrayTransformOrigin,
                    ) + fadeIn(animationSpec = tween(durationMillis = 180)),
                    exit = scaleOut(
                        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
                        targetScale = 0.9f,
                        transformOrigin = TrayTransformOrigin,
                    ) + fadeOut(animationSpec = tween(durationMillis = 120)),
                ) {
                    if (ownsAnyPack) {
                        EmojiPickerRow(
                            emojis = emojis,
                            onPick = { emoji ->
                                onBlast(emoji)
                                expanded = false
                            },
                        )
                    } else {
                        // No shop CTA here — opening the shop tab mid-game
                        // forfeits the seat. The greyed preview + caption
                        // tells the user where to look after the game.
                        EmptyEmojiPopup()
                    }
                }
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
        color = AppTheme.colors.surface,
        contentColor = AppTheme.colors.contentSecondary,
        radius = Radii.IconButton,
        contentPadding = PaddingValues(TriggerSize.padding),
    ) {
        Box(
            modifier = Modifier.size(TriggerSize.iconSize.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(Res.string.room_emoji_tray_cooldown, remainingSeconds),
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.contentSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Empty-state popup shown when the user owns no `emotes_*` pack. A
 * row of greyed-out preview glyphs sells the affordance, a caption
 * sets the expectation, and a primary CTA routes to the shop. The
 * popup uses the same surface chrome as [EmojiPickerRow] so the
 * shape continuity reads as "this is the same tray, just empty for
 * you right now."
 */
@Composable
private fun EmptyEmojiPopup(
    modifier: Modifier = Modifier,
) {
    val feltAccent = LocalFeltAccentSurface.current
    Surface(
        modifier = modifier,
        color = if (feltAccent != null) null else AppTheme.colors.surfaceRaised,
        colorOverride = feltAccent,
        contentColor = AppTheme.colors.content,
        radius = Radii.Card,
        contentPadding = PaddingValues(
            horizontal = Dimension.D400,
            vertical = Dimension.D400,
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Greyed-out preview row at the picker's normal CellSize so
            // the shape matches the live tray. Static row instead of
            // LazyRow — the preview list is fixed at 4 items, no need to
            // pay for lazy layout. EmojiButtons are `enabled = false` so
            // they don't surface ripple / role=Button to a11y; the alpha
            // sells the "you don't own these yet" affordance.
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimension.D100),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.alpha(0.4f),
            ) {
                EmotePackCatalog.SamplePreview.forEach { emoji ->
                    EmojiButton(
                        emoji = emoji,
                        size = CellSize,
                        backgroundColor = null,
                        enabled = false,
                        onClick = {},
                    )
                }
            }
            VerticalSpacerD300()
            Text(
                text = stringResource(Res.string.room_emoji_tray_empty_caption),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
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
    // Popup sits over the table, so the picker reads as alien chrome on
    // non-default felts. Read the felt-accent local for a "raised felt"
    // tone; falls through to surfaceSecondary outside the play screen.
    val feltAccent = LocalFeltAccentSurface.current
    Surface(
        modifier = modifier,
        color = if (feltAccent != null) null else AppTheme.colors.surfaceRaised,
        colorOverride = feltAccent,
        contentColor = AppTheme.colors.content,
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

// Pivot anchored to the tray's top-right corner so the scale animation
// reads as "unfurling from the trigger button" — which sits at TopEnd
// of the TopBar directly above the popup's top-right.
private val TrayTransformOrigin = TransformOrigin(pivotFractionX = 1f, pivotFractionY = 0f)

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
private fun TopBarEmojiButtonPreview_EmptyState() {
    PreviewContent {
        TopBarEmojiButton(
            emojis = emptyList(),
            cooldownEndsAtEpochMs = 0L,
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

@Preview
@Composable
private fun EmptyEmojiPopupPreview() {
    PreviewContent {
        EmptyEmojiPopup()
    }
}
