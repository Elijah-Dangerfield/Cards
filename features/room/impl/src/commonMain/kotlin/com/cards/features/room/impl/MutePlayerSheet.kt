package com.dangerfield.cards.features.room.impl

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.ListSection
import com.dangerfield.cards.libraries.ui.components.ListSectionItem
import com.dangerfield.cards.libraries.ui.components.ListItemAccessory
import com.dangerfield.cards.libraries.ui.components.dialog.BubbleSurface
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheetDragHandle
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.asDragHandle
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.resolveAvatarBackground
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD800
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Tap-an-opponent surface. The sheet wears the seat's avatar as its
 * top emoji bubble (DS chokepoint — same primitive the welcome and
 * shop sheets use), then a single Switch row to toggle persistent
 * mute via [PlayPokerAction.ToggleMutePlayer].
 *
 * Switch toggles in place — the sheet doesn't auto-dismiss. The user
 * can flip mute on and off without the sheet bouncing closed every
 * time, which is the standard switch-row behavior elsewhere in the app.
 *
 * In V1 single-player vs bots, no one but the human emits emoji, so
 * the mute set never actually filters anything visible today. The
 * sheet is still wired and persisted so that when MP / reactive-bot
 * blasts ship in V1.x (product-spec.md §5.5), the user's existing
 * mute preferences are already in place — they don't get a wall of
 * unfamiliar emoji at first exposure.
 */
@Composable
internal fun MutePlayerSheet(
    seat: SeatView,
    isMuted: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Wrap the seat's avatar hex into a ColorResource so the DS bubble
    // surface can carry it. Null / unparseable hex falls through to
    // surfaceSecondary via resolveAvatarBackground, matching how the
    // same seat renders elsewhere on the table.
    val bubbleColor = resolveAvatarBackground(seat.avatarBackgroundColorHex)
    val handle: BottomSheetDragHandle = topAccessoryEmoji(
        emoji = seat.emoji ?: seat.displayName.firstOrNull()?.uppercase() ?: "?",
        surface = BubbleSurface.Solid(
            ColorResource.FromColor(bubbleColor, "seatAvatar"),
        ),
    ).asDragHandle()

    BottomSheet(
        onDismissRequest = onDismiss,
        backgroundColor = AppTheme.colors.surfacePrimary,
        dragHandle = handle,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = seat.displayName,
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.onSurfacePrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().align(Alignment.CenterHorizontally),
            )
            // Level / bot-difficulty subtitle — moved here from the
            // opponents row to keep the table chrome compact. Hidden
            // when the seat has no badge (e.g. remote humans without a
            // known level), so the heading sits alone on those rows.
            seat.seatBadge?.let { badge ->
                VerticalSpacerD200()
                Text(
                    text = badge,
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.onSurfaceSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().align(Alignment.CenterHorizontally),
                )
            }
            VerticalSpacerD500()
            Text(
                text = "Hide their table emoji blasts on your screen. You can flip this back any time.",
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.onSurfaceSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().align(Alignment.CenterHorizontally),
            )
            VerticalSpacerD800()
            ListSection(
                items = listOf(
                    ListSectionItem(
                        headlineText = "Mute emoji",
                        supportingText = if (isMuted) {
                            "You won't see their table blasts."
                        } else {
                            "Their blasts will show on your screen."
                        },
                        accessory = ListItemAccessory.Switch(
                            checked = isMuted,
                            onCheckedChange = { onToggle() },
                        ),
                    ),
                ),
            )
        }
    }
}

@Preview
@Composable
private fun MutePlayerSheetPreview_BotUnmuted() {
    PreviewContent {
        MutePlayerSheet(
            seat = PreviewSamples.botSeat(index = 1, name = "Jane")
                .copy(seatBadge = "Bot · Standard"),
            isMuted = false,
            onToggle = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun MutePlayerSheetPreview_BotMuted() {
    PreviewContent {
        MutePlayerSheet(
            seat = PreviewSamples.botSeat(index = 2, name = "Maverick")
                .copy(seatBadge = "Bot · Challenging"),
            isMuted = true,
            onToggle = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun MutePlayerSheetPreview_NoBadge() {
    PreviewContent {
        MutePlayerSheet(
            seat = PreviewSamples.botSeat(index = 3, name = "Remote Human")
                .copy(seatBadge = null, emoji = null),
            isMuted = false,
            onToggle = {},
            onDismiss = {},
        )
    }
}
