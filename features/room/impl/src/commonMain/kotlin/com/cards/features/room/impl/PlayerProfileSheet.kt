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
import com.dangerfield.cards.system.VerticalSpacerD800
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Tap-an-opponent surface. Header carries the seat's identity; the
 * Settings list holds the per-seat preferences the local user controls.
 * Only emoji mute today; new sections (friend, profile-of-a-stranger)
 * land here as the social-graph todo lights up.
 */
@Composable
internal fun PlayerProfileSheet(
    seat: SeatView,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    onDismiss: () -> Unit,
) {
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
            VerticalSpacerD800()
            ListSection(
                title = "Settings",
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
                            onCheckedChange = { onToggleMute() },
                        ),
                    ),
                ),
            )
        }
    }
}

@Preview
@Composable
private fun PlayerProfileSheetPreview_BotUnmuted() {
    PreviewContent {
        PlayerProfileSheet(
            seat = PreviewSamples.botSeat(index = 1, name = "Jane")
                .copy(seatBadge = "Bot · Standard"),
            isMuted = false,
            onToggleMute = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun PlayerProfileSheetPreview_BotMuted() {
    PreviewContent {
        PlayerProfileSheet(
            seat = PreviewSamples.botSeat(index = 2, name = "Maverick")
                .copy(seatBadge = "Bot · Challenging"),
            isMuted = true,
            onToggleMute = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun PlayerProfileSheetPreview_NoBadge() {
    PreviewContent {
        PlayerProfileSheet(
            seat = PreviewSamples.botSeat(index = 3, name = "Remote Human")
                .copy(seatBadge = null, emoji = null),
            isMuted = false,
            onToggleMute = {},
            onDismiss = {},
        )
    }
}
