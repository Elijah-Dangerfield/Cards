package com.dangerfield.cards.features.room.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.ListSection
import com.dangerfield.cards.libraries.ui.components.ListSectionItem
import com.dangerfield.cards.libraries.ui.components.ListItemAccessory
import com.dangerfield.cards.libraries.ui.components.RadarChart
import com.dangerfield.cards.libraries.ui.components.dialog.BubbleSurface
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheetDragHandle
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.asDragHandle
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.resolveAvatarBackground
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD500
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Tap-an-opponent surface. Header carries the seat's identity; the
 * body stacks an at-a-glance "Playing style" card (bots only, derived
 * from [SeatView.personality]) and a Settings list of per-seat
 * preferences. New sections (friend, profile-of-a-stranger) land here
 * as the social-graph todo lights up.
 */
@Composable
internal fun PlayerProfileSheet(
    seat: SeatView,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    onDismiss: () -> Unit,
    botDifficultyLabel: String? = null,
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
        title = {
            Text(
                text = seat.displayName,
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.onSurfacePrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            seat.seatBadge?.let { badge ->
                VerticalSpacerD200()
                Text(
                    text = badge,
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.onSurfaceSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) {
        seat.personality?.let { personality ->
            PlayingStyleBlock(personality = personality)
            VerticalSpacerD500()
        }
        tenureRows(seat).takeIf { it.isNotEmpty() }?.let { rows ->
            ListSection(
                title = "At this table",
                items = rows,
            )
            VerticalSpacerD500()
        }
        if (seat.isBot) {
            botDifficultyLabel?.let { difficultyTierFor(it) }?.let { tier ->
                ListSection(
                    title = "Difficulty",
                    items = listOf(
                        ListSectionItem(
                            headlineText = tier.label,
                            supportingText = tier.description,
                            accessory = ListItemAccessory.None,
                        ),
                    ),
                )
                VerticalSpacerD500()
            }
        }
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

@Composable
private fun PlayingStyleBlock(personality: BotPersonality) {
    val style = playingStyleFor(personality)
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Playing style",
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.onSurfacePrimary,
        )
        VerticalSpacerD200()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(Radii.Card.shape)
                .background(AppTheme.colors.surfacePrimary.color)
                .padding(Dimension.D500),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = style.label,
                        typography = AppTheme.typography.Body.B600,
                        color = AppTheme.colors.onSurfacePrimary,
                    )
                    VerticalSpacerD200()
                    Text(
                        text = style.description,
                        typography = AppTheme.typography.Body.B500,
                        color = AppTheme.colors.onSurfaceSecondary,
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    RadarChart(axes = radarAxesFor(personality))
                }
            }
        }
    }
}

@Preview
@Composable
private fun PlayerProfileSheetPreview_BotUnmuted_TightPassive() {
    PreviewContent {
        PlayerProfileSheet(
            seat = PreviewSamples.botSeat(index = 1, name = "Jane")
                .copy(
                    seatBadge = "Bot · Standard",
                    personality = com.dangerfield.cards.libraries.bots.BotPersonality.Jane,
                    handsAtTable = 47,
                ),
            isMuted = false,
            onToggleMute = {},
            onDismiss = {},
            botDifficultyLabel = "Standard",
        )
    }
}

@Preview
@Composable
private fun PlayerProfileSheetPreview_BotMuted_Maniac() {
    PreviewContent {
        PlayerProfileSheet(
            seat = PreviewSamples.botSeat(index = 2, name = "Mike")
                .copy(
                    seatBadge = "Bot · Challenging",
                    personality = com.dangerfield.cards.libraries.bots.BotPersonality.Mike,
                ),
            isMuted = true,
            onToggleMute = {},
            onDismiss = {},
            botDifficultyLabel = "Challenging",
        )
    }
}

@Preview
@Composable
private fun PlayerProfileSheetPreview_Bot_LooseAggressive() {
    PreviewContent {
        PlayerProfileSheet(
            seat = PreviewSamples.botSeat(index = 3, name = "David")
                .copy(
                    seatBadge = "Bot · Casual",
                    personality = com.dangerfield.cards.libraries.bots.BotPersonality.David,
                ),
            isMuted = false,
            onToggleMute = {},
            onDismiss = {},
            botDifficultyLabel = "Casual",
        )
    }
}

@Preview
@Composable
private fun PlayerProfileSheetPreview_NoPersonality() {
    PreviewContent {
        PlayerProfileSheet(
            seat = PreviewSamples.botSeat(index = 3, name = "Remote Human")
                .copy(seatBadge = null, emoji = null, personality = null),
            isMuted = false,
            onToggleMute = {},
            onDismiss = {},
        )
    }
}
