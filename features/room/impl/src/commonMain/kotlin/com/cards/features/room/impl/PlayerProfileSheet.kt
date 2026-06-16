package com.dangerfield.cards.features.room.impl

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.room_player_profile_difficulty_section_title
import cards.libraries.resources.generated.resources.room_player_profile_mute_emoji_headline
import cards.libraries.resources.generated.resources.room_player_profile_mute_emoji_supporting_muted
import cards.libraries.resources.generated.resources.room_player_profile_mute_emoji_supporting_unmuted
import cards.libraries.resources.generated.resources.room_player_profile_playing_style_heading
import cards.libraries.resources.generated.resources.room_player_profile_settings_section_title
import cards.libraries.resources.generated.resources.room_player_profile_tenure_section_title
import cards.libraries.resources.generated.resources.room_seat_badge_bot_plain
import cards.libraries.resources.generated.resources.room_seat_badge_bot_with_difficulty
import cards.libraries.resources.generated.resources.room_seat_badge_level
import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.ListSection
import com.dangerfield.cards.libraries.ui.components.ListSectionItem
import com.dangerfield.cards.libraries.ui.components.ListItemAccessory
import com.dangerfield.cards.libraries.ui.components.PlayingStyleCard
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
import org.jetbrains.compose.resources.stringResource
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
        backgroundColor = AppTheme.colors.surface,
        dragHandle = handle,
        title = {
            Text(
                text = seat.displayName,
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.content,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            seat.seatBadge?.let { badge ->
                VerticalSpacerD200()
                Text(
                    text = badge.label(),
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.contentSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) {
        // Heat-map / radar chart is bot-only today: bots ship a deterministic
        // [BotPersonality]; humans have `personality == null` and therefore
        // can't render one. When MP human opponents gain a derived style
        // (raise/call/fold tendencies from public history), the render gate
        // here must additionally require ownership of `tool_opponent_style`
        // (the Opponent Style Reader utility in the shop). The product copy
        // already calls out that bot heat-maps are free via seat-tap; humans
        // are the paid path.
        if (seat.isBot) {
            seat.personality?.let { personality ->
                PlayingStyleBlock(personality = personality)
                VerticalSpacerD500()
            }
        }
        tenureRows(seat).takeIf { it.isNotEmpty() }?.let { rows ->
            ListSection(
                title = stringResource(Res.string.room_player_profile_tenure_section_title),
                items = rows,
            )
            VerticalSpacerD500()
        }
        if (seat.isBot) {
            botDifficultyLabel?.let { difficultyTierFor(it) }?.let { tier ->
                ListSection(
                    title = stringResource(Res.string.room_player_profile_difficulty_section_title),
                    items = listOf(
                        ListSectionItem(
                            headlineText = tier.label,
                            supportingText = stringResource(tier.description),
                            accessory = ListItemAccessory.None,
                        ),
                    ),
                )
                VerticalSpacerD500()
            }
        }
        ListSection(
            title = stringResource(Res.string.room_player_profile_settings_section_title),
            items = listOf(
                ListSectionItem(
                    headlineText = stringResource(Res.string.room_player_profile_mute_emoji_headline),
                    supportingText = if (isMuted) {
                        stringResource(Res.string.room_player_profile_mute_emoji_supporting_muted)
                    } else {
                        stringResource(Res.string.room_player_profile_mute_emoji_supporting_unmuted)
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
internal fun SeatBadge.label(): String = when (this) {
    is SeatBadge.Level -> stringResource(Res.string.room_seat_badge_level, level)
    is SeatBadge.BotWithDifficulty -> stringResource(
        Res.string.room_seat_badge_bot_with_difficulty,
        difficulty,
    )
    SeatBadge.BotPlain -> stringResource(Res.string.room_seat_badge_bot_plain)
}

@Composable
private fun PlayingStyleBlock(personality: BotPersonality) {
    val style = playingStyleFor(personality)
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.room_player_profile_playing_style_heading),
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.content,
        )
        VerticalSpacerD200()
        PlayingStyleCard(
            axes = radarAxesFor(personality),
            styleName = stringResource(style.label),
            description = stringResource(style.description),
        )
    }
}

@Preview
@Composable
private fun PlayerProfileSheetPreview_BotUnmuted_TightPassive() {
    PreviewContent {
        PlayerProfileSheet(
            seat = PreviewSamples.botSeat(index = 1, name = "Jane")
                .copy(
                    seatBadge = SeatBadge.BotWithDifficulty("Standard"),
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
                    seatBadge = SeatBadge.BotWithDifficulty("Challenging"),
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
                    seatBadge = SeatBadge.BotWithDifficulty("Casual"),
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
