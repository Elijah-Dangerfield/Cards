package com.dangerfield.cards.features.room.impl.ui

import com.dangerfield.cards.features.room.impl.SeatBadge
import com.dangerfield.cards.features.room.impl.SeatView
import com.dangerfield.cards.features.room.impl.shortLabel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.system.Dimension
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.room_player_profile_last_move_heading
import cards.libraries.resources.generated.resources.profile_player_card_view_blurb
import cards.libraries.resources.generated.resources.room_player_profile_add_friend_button
import cards.libraries.resources.generated.resources.room_player_profile_add_friend_button_sent
import cards.libraries.resources.generated.resources.room_player_profile_add_friend_headline
import cards.libraries.resources.generated.resources.room_player_profile_add_friend_supporting
import cards.libraries.resources.generated.resources.room_player_profile_friend_section_title
import cards.libraries.resources.generated.resources.room_player_profile_bot_callout_body
import cards.libraries.resources.generated.resources.room_player_profile_bot_callout_title
import cards.libraries.resources.generated.resources.room_player_profile_difficulty_section_title
import cards.libraries.resources.generated.resources.room_player_profile_mute_emoji_headline
import cards.libraries.resources.generated.resources.room_player_profile_mute_emoji_supporting_muted
import cards.libraries.resources.generated.resources.room_player_profile_mute_emoji_supporting_unmuted
import cards.libraries.resources.generated.resources.room_player_profile_playing_style_heading
import cards.libraries.resources.generated.resources.room_player_profile_report_button
import cards.libraries.resources.generated.resources.room_player_profile_report_button_sent
import cards.libraries.resources.generated.resources.room_player_profile_report_headline
import cards.libraries.resources.generated.resources.room_player_profile_report_section_title
import cards.libraries.resources.generated.resources.room_player_profile_report_supporting
import cards.libraries.resources.generated.resources.room_player_profile_settings_section_title
import cards.libraries.resources.generated.resources.room_player_profile_style_locked_body
import cards.libraries.resources.generated.resources.room_player_profile_style_locked_title
import cards.libraries.resources.generated.resources.room_player_profile_style_pending_body
import cards.libraries.resources.generated.resources.room_player_profile_tenure_section_title
import cards.libraries.resources.generated.resources.room_seat_badge_bot_plain
import cards.libraries.resources.generated.resources.room_seat_badge_bot_with_difficulty
import cards.libraries.resources.generated.resources.room_seat_badge_level
import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.libraries.cards.BotAvatarEmoji
import com.dangerfield.cards.libraries.cards.PlayStyleAxes
import com.dangerfield.cards.libraries.ui.components.toRadarAxes
import com.dangerfield.cards.libraries.ui.components.toStyleCopy
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.Banner
import com.dangerfield.cards.libraries.ui.components.BannerType
import com.dangerfield.cards.libraries.ui.components.ListSection
import com.dangerfield.cards.libraries.ui.components.ListSectionItem
import com.dangerfield.cards.libraries.ui.components.ListItemAccessory
import com.dangerfield.cards.libraries.ui.components.PlayerBadge
import com.dangerfield.cards.libraries.ui.components.PlayerCardContent
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
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
import androidx.compose.ui.tooling.preview.Preview

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
    isMePlayer: Boolean = false,
    badges: List<PlayerBadge> = emptyList(),
    onBadgeClick: (PlayerBadge) -> Unit = {},
    botDifficultyLabel: String? = null,
    /**
     * Human play-style to render: the user's own on their self-card, or a
     * human opponent's fetched public style. Null = none / not loaded.
     */
    playStyle: PlayStyleAxes? = null,
    /** Owns `tool_opponent_style` — gates the human-opponent style readout. */
    ownsOpponentStyleReader: Boolean = false,
    /**
     * Send a friend request to this human opponent. Null hides the Add-friend
     * section (bots, empty seats, your own card, or a seat with no stable user
     * id). The friend graph's only friending path is a played-with opponent, so
     * the section is appropriate only on a human opponent's card.
     */
    onAddFriend: (() -> Unit)? = null,
    /** True once a request to this opponent is outbound — flips the button to Sent. */
    friendRequestSent: Boolean = false,
    /**
     * File a report against this human opponent. Null hides the Report section
     * (bots, empty seats, your own card). Unlike [onAddFriend] this is not gated
     * on the social flag — reporting is a store-compliance path that stays
     * available on any human opponent.
     */
    onReport: (() -> Unit)? = null,
    /** True once a report against this opponent is filed — flips the button to Reported. */
    reportSent: Boolean = false,
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
    ) {
        // [ModalContent] places this content slot in a Box, so the stacked
        // sections must bring their own vertical layout — without this Column
        // they'd all render at the same origin and overlap. The scroll keeps
        // tall cards (bot style radar + tenure + difficulty + settings) reachable
        // on short screens instead of clipping at the sheet's max height (GAME-13),
        // matching HowToPlaySheet.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Identity block — shared with the Edit Profile preview so the card
            // can never drift. The avatar sits in the notch bubble above; this
            // is everything below it (name, level, title, permanent badge,
            // featured).
            PlayerCardContent(
                name = seat.displayName,
                level = (seat.seatBadge as? SeatBadge.Level)?.level,
                badges = badges,
                onBadgeClick = onBadgeClick,
                modifier = Modifier.fillMaxWidth(),
            )
            VerticalSpacerD500()
            // The seat's last action this round, echoed with the exact felt
            // symbols (green check / gold pill). Tapping the avatar is the
            // bigger-target way in — this is where the move now lives, not a
            // separate dialog.
            seat.lastAction?.let { action ->
                SeatLastMoveBlock(action = action)
                VerticalSpacerD500()
            }
            // Play-style readout. Bots ship a deterministic [BotPersonality] and
            // render free via seat-tap. Humans get a derived style: your own is
            // free on your self-card; a human opponent's is gated behind the
            // Opponent Style Reader (`tool_opponent_style`) — public info, paid
            // convenience, as the shop copy says.
            if (seat.isBot) {
                seat.personality?.let { personality ->
                    BotPlayingStyleBlock(personality = personality)
                    VerticalSpacerD500()
                }
                Banner(
                    type = BannerType.Info,
                    title = stringResource(Res.string.room_player_profile_bot_callout_title),
                    body = stringResource(Res.string.room_player_profile_bot_callout_body),
                    leading = { Text(BotAvatarEmoji) },
                    modifier = Modifier.fillMaxWidth(),
                )
                VerticalSpacerD500()
            } else if (!seat.seatEmpty) {
                HumanPlayingStyleSection(
                    playStyle = playStyle,
                    isMePlayer = isMePlayer,
                    ownsOpponentStyleReader = ownsOpponentStyleReader,
                )
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
            if (!isMePlayer && onAddFriend != null) {
                ListSection(
                    title = stringResource(Res.string.room_player_profile_friend_section_title),
                    items = listOf(
                        ListSectionItem(
                            headlineText = stringResource(Res.string.room_player_profile_add_friend_headline),
                            supportingText = stringResource(Res.string.room_player_profile_add_friend_supporting),
                            accessory = ListItemAccessory.Custom {
                                ButtonSecondary(
                                    onClick = onAddFriend,
                                    size = ButtonSize.Small,
                                    enabled = !friendRequestSent,
                                ) {
                                    Text(
                                        text = stringResource(
                                            if (friendRequestSent) {
                                                Res.string.room_player_profile_add_friend_button_sent
                                            } else {
                                                Res.string.room_player_profile_add_friend_button
                                            },
                                        ),
                                    )
                                }
                            },
                        ),
                    ),
                )
                VerticalSpacerD500()
            }
            if (isMePlayer) {
                // Your own card: no mute (can't mute yourself); instead remind
                // you this is the public identity others see.
                Text(
                    text = stringResource(Res.string.profile_player_card_view_blurb),
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.contentSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
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
            if (onReport != null) {
                VerticalSpacerD500()
                ListSection(
                    title = stringResource(Res.string.room_player_profile_report_section_title),
                    items = listOf(
                        ListSectionItem(
                            headlineText = stringResource(Res.string.room_player_profile_report_headline),
                            supportingText = stringResource(Res.string.room_player_profile_report_supporting),
                            accessory = ListItemAccessory.Custom {
                                ButtonSecondary(
                                    onClick = onReport,
                                    size = ButtonSize.Small,
                                    enabled = !reportSent,
                                ) {
                                    Text(
                                        text = stringResource(
                                            if (reportSent) {
                                                Res.string.room_player_profile_report_button_sent
                                            } else {
                                                Res.string.room_player_profile_report_button
                                            },
                                        ),
                                    )
                                }
                            },
                        ),
                    ),
                )
            }
        }
    }
}

/**
 * The seat's most recent action, shown on the Player Card with the same chip
 * the felt draws (green check, neutral call/fold pill, gold chips-in pill)
 * plus a worded label. A fold persists here for the whole hand — it's the
 * only explanation the greyed seat gets (GAME-17).
 */
@Composable
private fun SeatLastMoveBlock(action: PlayerAction) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.room_player_profile_last_move_heading),
            typography = AppTheme.typography.Body.B400.Bold,
            color = AppTheme.colors.contentSecondary,
        )
        VerticalSpacerD200()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimension.D300),
        ) {
            SeatActionChip(
                action = action,
                cutoutColor = AppTheme.colors.surface.color,
            )
            Text(
                text = action.shortLabel(),
                typography = AppTheme.typography.Body.B600.SemiBold,
                color = AppTheme.colors.content,
            )
        }
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
private fun BotPlayingStyleBlock(personality: BotPersonality) {
    val style = playingStyleFor(personality)
    PlayingStyleBlock(
        axes = radarAxesFor(personality),
        styleName = stringResource(style.label),
        description = stringResource(style.description),
    )
}

/**
 * Human play-style: free on your own self-card, gated behind the Opponent
 * Style Reader for a human opponent. Renders a "keep playing" / "unlock"
 * fallback when there's nothing (yet) to show.
 */
@Composable
private fun HumanPlayingStyleSection(
    playStyle: PlayStyleAxes?,
    isMePlayer: Boolean,
    ownsOpponentStyleReader: Boolean,
) {
    val ready = playStyle?.takeIf { it.hasEnoughData }
    when {
        isMePlayer -> {
            // Don't nag yourself with an upsell; just show the radar once it exists.
            if (ready != null) {
                HumanPlayingStyleBlock(ready)
                VerticalSpacerD500()
            }
        }
        ownsOpponentStyleReader -> {
            if (ready != null) {
                HumanPlayingStyleBlock(ready)
            } else {
                Banner(
                    type = BannerType.Info,
                    title = stringResource(Res.string.room_player_profile_playing_style_heading),
                    body = stringResource(Res.string.room_player_profile_style_pending_body),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            VerticalSpacerD500()
        }
        else -> {
            Banner(
                type = BannerType.Info,
                title = stringResource(Res.string.room_player_profile_style_locked_title),
                body = stringResource(Res.string.room_player_profile_style_locked_body),
                leading = { Text("🔍") },
                modifier = Modifier.fillMaxWidth(),
            )
            VerticalSpacerD500()
        }
    }
}

@Composable
private fun HumanPlayingStyleBlock(style: PlayStyleAxes) {
    val copy = style.toStyleCopy()
    PlayingStyleBlock(
        axes = style.toRadarAxes(),
        styleName = stringResource(copy.label),
        description = stringResource(copy.description),
    )
}

@Composable
private fun PlayingStyleBlock(
    axes: List<com.dangerfield.cards.libraries.ui.components.RadarAxis>,
    styleName: String,
    description: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.room_player_profile_playing_style_heading),
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.content,
        )
        VerticalSpacerD200()
        PlayingStyleCard(
            axes = axes,
            styleName = styleName,
            description = description,
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
                    lastAction = PlayerAction.Raise(totalStreetContribution = 60, raiseAmount = 40),
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
private fun PlayerProfileSheetPreview_HumanOpponent_AddFriend() {
    PreviewContent {
        PlayerProfileSheet(
            seat = PreviewSamples.botSeat(index = 4, name = "Sam")
                .copy(isBot = false, userId = "user-sam", seatBadge = SeatBadge.Level(7)),
            isMuted = false,
            onToggleMute = {},
            onDismiss = {},
            onAddFriend = {},
            onReport = {},
        )
    }
}

@Preview
@Composable
private fun PlayerProfileSheetPreview_HumanOpponent_Reported() {
    PreviewContent {
        PlayerProfileSheet(
            seat = PreviewSamples.botSeat(index = 4, name = "Sam")
                .copy(isBot = false, userId = "user-sam", seatBadge = SeatBadge.Level(7)),
            isMuted = false,
            onToggleMute = {},
            onDismiss = {},
            onReport = {},
            reportSent = true,
        )
    }
}

@Preview
@Composable
private fun PlayerProfileSheetPreview_HumanOpponent_RequestSent() {
    PreviewContent {
        PlayerProfileSheet(
            seat = PreviewSamples.botSeat(index = 4, name = "Sam")
                .copy(isBot = false, userId = "user-sam", seatBadge = SeatBadge.Level(7)),
            isMuted = false,
            onToggleMute = {},
            onDismiss = {},
            onAddFriend = {},
            friendRequestSent = true,
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
