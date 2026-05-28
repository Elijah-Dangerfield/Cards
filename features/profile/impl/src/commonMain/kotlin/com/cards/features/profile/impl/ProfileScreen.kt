package com.dangerfield.cards.features.profile.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.month_april
import cards.libraries.resources.generated.resources.month_august
import cards.libraries.resources.generated.resources.month_december
import cards.libraries.resources.generated.resources.month_february
import cards.libraries.resources.generated.resources.month_january
import cards.libraries.resources.generated.resources.month_july
import cards.libraries.resources.generated.resources.month_june
import cards.libraries.resources.generated.resources.month_march
import cards.libraries.resources.generated.resources.month_may
import cards.libraries.resources.generated.resources.month_november
import cards.libraries.resources.generated.resources.month_october
import cards.libraries.resources.generated.resources.month_september
import cards.libraries.resources.generated.resources.month_unknown
import cards.libraries.resources.generated.resources.profile_about_privacy
import cards.libraries.resources.generated.resources.profile_about_terms
import cards.libraries.resources.generated.resources.profile_account_my_items_headline
import cards.libraries.resources.generated.resources.profile_account_my_items_supporting
import cards.libraries.resources.generated.resources.profile_account_notifications_headline
import cards.libraries.resources.generated.resources.profile_account_notifications_supporting_default
import cards.libraries.resources.generated.resources.profile_account_notifications_supporting_unread
import cards.libraries.resources.generated.resources.profile_account_rank_headline
import cards.libraries.resources.generated.resources.profile_account_rank_supporting
import cards.libraries.resources.generated.resources.profile_account_rank_unranked
import cards.libraries.resources.generated.resources.profile_account_stats_headline
import cards.libraries.resources.generated.resources.profile_account_stats_supporting
import cards.libraries.resources.generated.resources.profile_app_version_footer
import cards.libraries.resources.generated.resources.profile_avatar_edit_a11y
import cards.libraries.resources.generated.resources.profile_bot_speed_fast
import cards.libraries.resources.generated.resources.profile_bot_speed_normal
import cards.libraries.resources.generated.resources.profile_bot_speed_slow
import cards.libraries.resources.generated.resources.profile_claim_card_body
import cards.libraries.resources.generated.resources.profile_claim_card_cta
import cards.libraries.resources.generated.resources.profile_claim_card_title
import cards.libraries.resources.generated.resources.profile_debug_qa_headline
import cards.libraries.resources.generated.resources.profile_debug_qa_supporting
import cards.libraries.resources.generated.resources.profile_delete_account_button
import cards.libraries.resources.generated.resources.profile_gameplay_bot_speed_headline
import cards.libraries.resources.generated.resources.profile_gameplay_bot_speed_supporting
import cards.libraries.resources.generated.resources.profile_gameplay_turn_feedback_headline
import cards.libraries.resources.generated.resources.profile_gameplay_turn_feedback_supporting
import cards.libraries.resources.generated.resources.profile_gameplay_tutorial_headline
import cards.libraries.resources.generated.resources.profile_gameplay_tutorial_supporting
import cards.libraries.resources.generated.resources.profile_header_member_since
import cards.libraries.resources.generated.resources.profile_level_summary_level_xp
import cards.libraries.resources.generated.resources.profile_level_summary_to_next_level
import cards.libraries.resources.generated.resources.profile_section_about
import cards.libraries.resources.generated.resources.profile_section_account
import cards.libraries.resources.generated.resources.profile_section_debug
import cards.libraries.resources.generated.resources.profile_section_gameplay
import cards.libraries.resources.generated.resources.profile_section_support
import cards.libraries.resources.generated.resources.profile_sign_out_button
import cards.libraries.resources.generated.resources.profile_sign_out_button_progress
import cards.libraries.resources.generated.resources.profile_sign_out_dialog_anonymous_body
import cards.libraries.resources.generated.resources.profile_sign_out_dialog_anonymous_title
import cards.libraries.resources.generated.resources.profile_sign_out_dialog_cancel_button
import cards.libraries.resources.generated.resources.profile_sign_out_dialog_claimed_body
import cards.libraries.resources.generated.resources.profile_sign_out_dialog_claimed_title
import cards.libraries.resources.generated.resources.profile_sign_out_dialog_confirm_button
import cards.libraries.resources.generated.resources.profile_support_bug_headline
import cards.libraries.resources.generated.resources.profile_support_bug_supporting
import cards.libraries.resources.generated.resources.profile_support_feedback_headline
import cards.libraries.resources.generated.resources.profile_support_feedback_supporting
import cards.libraries.resources.generated.resources.profile_turn_feedback_mute
import cards.libraries.resources.generated.resources.profile_turn_feedback_sound
import cards.libraries.resources.generated.resources.profile_turn_feedback_vibrate
import com.dangerfield.cards.libraries.cards.LevelProgress
import com.dangerfield.cards.libraries.cards.formatThousands
import com.dangerfield.cards.libraries.cards.levelProgressFor
import com.dangerfield.cards.libraries.ui.system.color.LevelProgressGradient
import com.dangerfield.cards.libraries.ui.PreviewBottomBar
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.icon.Icon
import com.dangerfield.cards.libraries.ui.components.icon.IconSize
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.BasicDropdownMenuItem
import com.dangerfield.cards.libraries.ui.components.BottomBarSpacer
import com.dangerfield.cards.libraries.ui.components.DropdownMenu
import com.dangerfield.cards.libraries.ui.components.ListItemAccessory
import com.dangerfield.cards.libraries.ui.components.ListSection
import com.dangerfield.cards.libraries.ui.components.ListSectionItem
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD100
import com.dangerfield.cards.system.VerticalSpacerD1100
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD800
import com.dangerfield.cards.system.VerticalSpacerD900
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

data class ProfileSettings(
    val displayName: String,
    val avatarEmoji: String?,
    val avatarBackgroundColor: String?,
    val rank: Int,
    val xp: Long,
    val isAnonymous: Boolean,
    val botSpeed: com.dangerfield.cards.libraries.cards.BotSpeed,
    val turnFeedback: com.dangerfield.cards.libraries.cards.TurnFeedback,
    val appVersion: String,
    val unreadNotificationCount: Int = 0,
    val showQaMenu: Boolean = false,
    val memberSince: kotlin.time.Instant? = null,
)

@Composable
fun ProfileScreen(
    settings: ProfileSettings,
    onClaimAccount: () -> Unit,
    onEditProfile: () -> Unit,
    onOpenMyItems: () -> Unit,
    onOpenNotifications: () -> Unit,
    onBotSpeedChange: (com.dangerfield.cards.libraries.cards.BotSpeed) -> Unit,
    onTurnFeedbackChange: (com.dangerfield.cards.libraries.cards.TurnFeedback) -> Unit,
    onTapRank: () -> Unit,
    onTapXp: () -> Unit,
    onSendFeedback: () -> Unit,
    onReportBug: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onTermsOfService: () -> Unit,
    onDeleteAccount: () -> Unit,
    onSignOut: () -> Unit,
    isSigningOut: Boolean = false,
    onOpenQaMenu: () -> Unit = {},
    onOpenTutorial: () -> Unit = {},
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    var showSignOutDialog by remember { mutableStateOf(false) }
    Screen(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .screenContentPadding(paddingValues = padding)
                .padding(vertical = 16.dp),
        ) {
            ProfileHeader(settings = settings, onEditProfile = onEditProfile)
            VerticalSpacerD900()

            if (settings.isAnonymous) {
                ClaimAccountCard(onClaimAccount = onClaimAccount)
                VerticalSpacerD800()
            }

            ListSection(
                title = stringResource(Res.string.profile_section_account),
                items = listOf(
                    ListSectionItem(
                        headlineText = stringResource(Res.string.profile_account_notifications_headline),
                        supportingText = if (settings.unreadNotificationCount > 0) {
                            stringResource(
                                Res.string.profile_account_notifications_supporting_unread,
                                settings.unreadNotificationCount,
                            )
                        } else {
                            stringResource(Res.string.profile_account_notifications_supporting_default)
                        },
                        onClick = onOpenNotifications,
                    ),
                    ListSectionItem(
                        headlineText = stringResource(Res.string.profile_account_my_items_headline),
                        supportingText = stringResource(Res.string.profile_account_my_items_supporting),
                        onClick = onOpenMyItems,
                    ),
                    ListSectionItem(
                        headlineText = stringResource(Res.string.profile_account_rank_headline),
                        supportingText = stringResource(Res.string.profile_account_rank_supporting),
                        accessory = com.dangerfield.cards.libraries.ui.components.ListItemAccessory.Text(
                            text = if (settings.rank <= 0) {
                                stringResource(Res.string.profile_account_rank_unranked)
                            } else {
                                settings.rank.toString()
                            },
                        ),
                        onClick = onTapRank,
                    ),
                    ListSectionItem(
                        headlineText = stringResource(Res.string.profile_account_stats_headline),
                        supportingText = stringResource(Res.string.profile_account_stats_supporting),
                        accessory = com.dangerfield.cards.libraries.ui.components.ListItemAccessory.Text(
                            text = settings.xp.toString(),
                        ),
                        onClick = onTapXp,
                    ),
                ),
            )

            VerticalSpacerD800()

            GameplaySection(
                botSpeed = settings.botSpeed,
                turnFeedback = settings.turnFeedback,
                onBotSpeedChange = onBotSpeedChange,
                onTurnFeedbackChange = onTurnFeedbackChange,
                onOpenTutorial = onOpenTutorial,
            )

            VerticalSpacerD800()

            ListSection(
                title = stringResource(Res.string.profile_section_support),
                items = listOf(
                    ListSectionItem(
                        headlineText = stringResource(Res.string.profile_support_feedback_headline),
                        supportingText = stringResource(Res.string.profile_support_feedback_supporting),
                        onClick = onSendFeedback,
                    ),
                    ListSectionItem(
                        headlineText = stringResource(Res.string.profile_support_bug_headline),
                        supportingText = stringResource(Res.string.profile_support_bug_supporting),
                        onClick = onReportBug,
                    ),
                ),
            )

            VerticalSpacerD800()

            ListSection(
                title = stringResource(Res.string.profile_section_about),
                items = listOf(
                    ListSectionItem(
                        headlineText = stringResource(Res.string.profile_about_privacy),
                        onClick = onPrivacyPolicy,
                    ),
                    ListSectionItem(
                        headlineText = stringResource(Res.string.profile_about_terms),
                        onClick = onTermsOfService,
                    ),
                ),
            )

            // Anonymous "sign out" is meaningless — there's no account to
            // sign back into, and we don't want to dangle "abandon progress"
            // as a primary action. The Claim Account card above is the right
            // path forward for guests. Claimed accounts get the red button
            // pair below.
            if (!settings.isAnonymous) {
                VerticalSpacerD1100()
                com.dangerfield.cards.libraries.ui.components.button.ButtonDanger(
                    onClick = { if (!isSigningOut) showSignOutDialog = true },
                    style = com.dangerfield.cards.libraries.ui.components.button.ButtonStyle.Outlined,
                    enabled = !isSigningOut,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (isSigningOut) {
                            stringResource(Res.string.profile_sign_out_button_progress)
                        } else {
                            stringResource(Res.string.profile_sign_out_button)
                        }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                com.dangerfield.cards.libraries.ui.components.button.ButtonDanger(
                    onClick = onDeleteAccount,
                    style = com.dangerfield.cards.libraries.ui.components.button.ButtonStyle.Text,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.profile_delete_account_button))
                }
            }

            if (settings.showQaMenu) {
                VerticalSpacerD800()
                ListSection(
                    title = stringResource(Res.string.profile_section_debug),
                    items = listOf(
                        ListSectionItem(
                            headlineText = stringResource(Res.string.profile_debug_qa_headline),
                            supportingText = stringResource(Res.string.profile_debug_qa_supporting),
                            onClick = onOpenQaMenu,
                        ),
                    ),
                )
            }

            VerticalSpacerD1100()
            // App version as a quiet, centered footer rather than a table cell.
            // It's a "where am I in the release cycle" reference, not an action.
            Text(
                text = stringResource(Res.string.profile_app_version_footer, settings.appVersion),
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            BottomBarSpacer()
        }
    }

    if (showSignOutDialog) {
        SignOutConfirmDialog(
            isAnonymous = settings.isAnonymous,
            onConfirm = {
                showSignOutDialog = false
                onSignOut()
            },
            onDismiss = { showSignOutDialog = false },
        )
    }
}

@Composable
private fun SignOutConfirmDialog(
    isAnonymous: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = if (isAnonymous) {
        stringResource(Res.string.profile_sign_out_dialog_anonymous_title)
    } else {
        stringResource(Res.string.profile_sign_out_dialog_claimed_title)
    }
    val body = if (isAnonymous) {
        stringResource(Res.string.profile_sign_out_dialog_anonymous_body)
    } else {
        stringResource(Res.string.profile_sign_out_dialog_claimed_body)
    }
    com.dangerfield.cards.libraries.ui.components.dialog.Dialog(
        onDismissRequest = onDismiss,
        topAccessory = com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji(emoji = "👋"),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = title,
                typography = AppTheme.typography.Heading.H600,
                color = AppTheme.colors.onSurfacePrimary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = body,
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.onSurfaceSecondary,
            )
            Spacer(modifier = Modifier.height(20.dp))
            com.dangerfield.cards.libraries.ui.components.button.Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.profile_sign_out_dialog_confirm_button))
            }
            Spacer(modifier = Modifier.height(8.dp))
            com.dangerfield.cards.libraries.ui.components.button.Button(
                onClick = onDismiss,
                style = com.dangerfield.cards.libraries.ui.components.button.ButtonStyle.Text,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.profile_sign_out_dialog_cancel_button))
            }
        }
    }
}

private val turnFeedbackPickerOptions: List<com.dangerfield.cards.libraries.cards.TurnFeedback> =
    com.dangerfield.cards.libraries.cards.TurnFeedback.entries
        .filter { it != com.dangerfield.cards.libraries.cards.TurnFeedback.Sound }

private fun com.dangerfield.cards.libraries.cards.TurnFeedback.pickerDisplayValue():
    com.dangerfield.cards.libraries.cards.TurnFeedback =
    if (this == com.dangerfield.cards.libraries.cards.TurnFeedback.Sound) {
        com.dangerfield.cards.libraries.cards.TurnFeedback.Vibrate
    } else {
        this
    }

@Composable
private fun GameplaySection(
    botSpeed: com.dangerfield.cards.libraries.cards.BotSpeed,
    turnFeedback: com.dangerfield.cards.libraries.cards.TurnFeedback,
    onBotSpeedChange: (com.dangerfield.cards.libraries.cards.BotSpeed) -> Unit,
    onTurnFeedbackChange: (com.dangerfield.cards.libraries.cards.TurnFeedback) -> Unit,
    onOpenTutorial: () -> Unit = {},
) {
    var botSpeedExpanded by remember { mutableStateOf(false) }
    var turnFeedbackExpanded by remember { mutableStateOf(false) }

    ListSection(
        title = stringResource(Res.string.profile_section_gameplay),
        items = listOf(
            ListSectionItem(
                headlineText = stringResource(Res.string.profile_gameplay_tutorial_headline),
                supportingText = stringResource(Res.string.profile_gameplay_tutorial_supporting),
                onClick = onOpenTutorial,
            ),
            ListSectionItem(
                headlineText = stringResource(Res.string.profile_gameplay_bot_speed_headline),
                supportingText = stringResource(Res.string.profile_gameplay_bot_speed_supporting),
                accessory = ListItemAccessory.Custom {
                    DropdownAccessory(
                        text = stringResource(botSpeed.labelResource()),
                        expanded = botSpeedExpanded,
                        onDismiss = { botSpeedExpanded = false },
                        options = com.dangerfield.cards.libraries.cards.BotSpeed.entries.toList(),
                        label = { stringResource(it.labelResource()) },
                        onSelect = {
                            botSpeedExpanded = false
                            onBotSpeedChange(it)
                        },
                    )
                },
                onClick = { botSpeedExpanded = true },
            ),
            ListSectionItem(
                headlineText = stringResource(Res.string.profile_gameplay_turn_feedback_headline),
                supportingText = stringResource(Res.string.profile_gameplay_turn_feedback_supporting),
                accessory = ListItemAccessory.Custom {
                    val displayed = turnFeedback.pickerDisplayValue()
                    DropdownAccessory(
                        text = stringResource(displayed.labelResource()),
                        expanded = turnFeedbackExpanded,
                        onDismiss = { turnFeedbackExpanded = false },
                        options = turnFeedbackPickerOptions,
                        label = { stringResource(it.labelResource()) },
                        onSelect = {
                            turnFeedbackExpanded = false
                            onTurnFeedbackChange(it)
                        },
                    )
                },
                onClick = { turnFeedbackExpanded = true },
            ),
        ),
    )
}

private fun com.dangerfield.cards.libraries.cards.BotSpeed.labelResource(): StringResource =
    when (this) {
        com.dangerfield.cards.libraries.cards.BotSpeed.Slow -> Res.string.profile_bot_speed_slow
        com.dangerfield.cards.libraries.cards.BotSpeed.Normal -> Res.string.profile_bot_speed_normal
        com.dangerfield.cards.libraries.cards.BotSpeed.Fast -> Res.string.profile_bot_speed_fast
    }

private fun com.dangerfield.cards.libraries.cards.TurnFeedback.labelResource(): StringResource =
    when (this) {
        com.dangerfield.cards.libraries.cards.TurnFeedback.Mute -> Res.string.profile_turn_feedback_mute
        com.dangerfield.cards.libraries.cards.TurnFeedback.Sound -> Res.string.profile_turn_feedback_sound
        com.dangerfield.cards.libraries.cards.TurnFeedback.Vibrate -> Res.string.profile_turn_feedback_vibrate
    }

@Composable
private fun <T> DropdownAccessory(
    text: String,
    expanded: Boolean,
    onDismiss: () -> Unit,
    options: List<T>,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Box {
        Text(
            text = text,
            typography = AppTheme.typography.Body.B600,
            color = AppTheme.colors.onSurfaceSecondary,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
        ) {
            options.forEach { option ->
                BasicDropdownMenuItem(
                    text = { Text(text = label(option)) },
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun ProfileHeader(
    settings: ProfileSettings,
    onEditProfile: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // No .clip(CircleShape) on the wrapper — the badge tucks slightly
        // outside the avatar disc and would otherwise be cut off by a
        // circular clip on the bounding box.
        Box(
            modifier = Modifier.clickable(onClick = onEditProfile),
            contentAlignment = Alignment.Center,
        ) {
            AvatarCircle(
                name = settings.displayName,
                size = Dimension.D1900,
                typography = AppTheme.typography.Heading.H1000,
                // Avatar is server-authoritative — always prefer the user's
                // picked emoji. Anonymous users still get an emoji from the
                // starter pack at signup, so this should be present in both
                // states; the null fallback covers the bootstrap window.
                emoji = settings.avatarEmoji,
                backgroundColorHex = settings.avatarBackgroundColor,
            )
            // Border matches the page background so the badge reads as
            // lifted off the avatar disc rather than embedded in it.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(AppTheme.colors.surfaceSecondary.color)
                    .border(
                        width = 2.dp,
                        color = AppTheme.colors.background.color,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon = Icons.Pencil(stringResource(Res.string.profile_avatar_edit_a11y)),
                    size = IconSize.Small,
                    color = AppTheme.colors.onSurfaceSecondary,
                )
            }
        }
        VerticalSpacerD500()
        Text(
            text = settings.displayName,
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.text,
            textAlign = TextAlign.Center,
        )
        settings.memberSince?.let { createdAt ->
            VerticalSpacerD100()
            Text(
                text = formatMemberSince(createdAt),
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
        VerticalSpacerD500()
        LevelSummary(
            progress = remember(settings.xp) { levelProgressFor(settings.xp) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun formatMemberSince(createdAt: kotlin.time.Instant): String {
    val local = createdAt.toLocalDateTime(TimeZone.currentSystemDefault())
    return stringResource(
        Res.string.profile_header_member_since,
        stringResource(monthResource(local.monthNumber)),
        local.year,
    )
}

private fun monthResource(monthNumber: Int): StringResource = when (monthNumber) {
    1 -> Res.string.month_january
    2 -> Res.string.month_february
    3 -> Res.string.month_march
    4 -> Res.string.month_april
    5 -> Res.string.month_may
    6 -> Res.string.month_june
    7 -> Res.string.month_july
    8 -> Res.string.month_august
    9 -> Res.string.month_september
    10 -> Res.string.month_october
    11 -> Res.string.month_november
    12 -> Res.string.month_december
    else -> Res.string.month_unknown
}

/**
 * Compact level summary for the profile header: "Level N · X XP", a slim
 * progress bar, and "X XP to level N+1". Mirrors the visual treatment of
 * [com.dangerfield.cards.features.progression.impl.StatsScreen]'s hero
 * so the same level state reads identically across both surfaces.
 */
@Composable
private fun LevelSummary(progress: LevelProgress, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(
                Res.string.profile_level_summary_level_xp,
                progress.level,
                formatThousands(progress.totalXp),
            ),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.text,
        )
        LevelProgressBar(
            fraction = progress.fraction,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(
                Res.string.profile_level_summary_to_next_level,
                formatThousands(progress.xpToNextLevel),
                progress.level + 1,
            ),
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun LevelProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(8.dp)
            .clip(Radii.Round.shape)
            .background(AppTheme.colors.surfacePrimary.color),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(8.dp)
                .clip(Radii.Round.shape)
                .background(LevelProgressGradient),
        )
    }
}

@Composable
private fun ClaimAccountCard(onClaimAccount: () -> Unit) {
    com.dangerfield.cards.libraries.ui.components.Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AppTheme.colors.accentPrimary,
        contentColor = AppTheme.colors.text,
        radius = Radii.Card,
        onClick = onClaimAccount,
        bounceScale = 0.97f,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(Res.string.profile_claim_card_title),
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.text,
            )
            Text(
                text = stringResource(Res.string.profile_claim_card_body),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.text,
            )
            VerticalSpacerD100()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Radii.R500.shape)
                    .background(AppTheme.colors.surfaceSecondary.color)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.profile_claim_card_cta),
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.text,
                )
            }
        }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@androidx.compose.runtime.Composable
private fun ProfileScreenPreview_Anonymous() {
    PreviewContent(bottomBar = PreviewBottomBar.Profile) {
        ProfileScreen(
            settings = ProfileSettings(
                displayName = "Anon-1742",
                avatarEmoji = "🦊",
                avatarBackgroundColor = null,
                rank = 1200,
                xp = 60,
                isAnonymous = true,
                botSpeed = com.dangerfield.cards.libraries.cards.BotSpeed.Normal,
                turnFeedback = com.dangerfield.cards.libraries.cards.TurnFeedback.Vibrate,
                appVersion = "0.1.0",
                showQaMenu = false,
            ),
            onClaimAccount = {},
            onEditProfile = {},
            onOpenMyItems = {},
            onOpenNotifications = {},
            onBotSpeedChange = {},
            onTurnFeedbackChange = {},
            onTapRank = {},
            onTapXp = {},
            onSendFeedback = {},
            onReportBug = {},
            onPrivacyPolicy = {},
            onTermsOfService = {},
            onDeleteAccount = {},
            onSignOut = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@androidx.compose.runtime.Composable
private fun ProfileScreenPreview_Claimed() {
    PreviewContent(bottomBar = PreviewBottomBar.Profile) {
        ProfileScreen(
            settings = ProfileSettings(
                displayName = "Elijah",
                avatarEmoji = "🦄",
                avatarBackgroundColor = "#7555ff",
                rank = 1820,
                xp = 12_400,
                isAnonymous = false,
                botSpeed = com.dangerfield.cards.libraries.cards.BotSpeed.Fast,
                turnFeedback = com.dangerfield.cards.libraries.cards.TurnFeedback.Vibrate,
                appVersion = "0.1.0",
                showQaMenu = false,
                memberSince = kotlin.time.Instant.parse("2026-03-12T00:00:00Z"),
            ),
            onClaimAccount = {},
            onEditProfile = {},
            onOpenMyItems = {},
            onOpenNotifications = {},
            onBotSpeedChange = {},
            onTurnFeedbackChange = {},
            onTapRank = {},
            onTapXp = {},
            onSendFeedback = {},
            onReportBug = {},
            onPrivacyPolicy = {},
            onTermsOfService = {},
            onDeleteAccount = {},
            onSignOut = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@androidx.compose.runtime.Composable
private fun ProfileScreenPreview_SigningOut() {
    PreviewContent(bottomBar = PreviewBottomBar.Profile) {
        ProfileScreen(
            settings = ProfileSettings(
                displayName = "Elijah",
                avatarEmoji = "🦄",
                avatarBackgroundColor = "#7555ff",
                rank = 1820,
                xp = 12_400,
                isAnonymous = false,
                botSpeed = com.dangerfield.cards.libraries.cards.BotSpeed.Normal,
                turnFeedback = com.dangerfield.cards.libraries.cards.TurnFeedback.Vibrate,
                appVersion = "0.1.0",
                showQaMenu = false,
            ),
            onClaimAccount = {},
            onEditProfile = {},
            onOpenMyItems = {},
            onOpenNotifications = {},
            onBotSpeedChange = {},
            onTurnFeedbackChange = {},
            onTapRank = {},
            onTapXp = {},
            onSendFeedback = {},
            onReportBug = {},
            onPrivacyPolicy = {},
            onTermsOfService = {},
            onDeleteAccount = {},
            onSignOut = {},
            isSigningOut = true,
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@androidx.compose.runtime.Composable
private fun ProfileScreenPreview_WithUnreadNotifications() {
    PreviewContent(bottomBar = PreviewBottomBar.Profile) {
        ProfileScreen(
            settings = ProfileSettings(
                displayName = "Elijah",
                avatarEmoji = "🦄",
                avatarBackgroundColor = "#7555ff",
                rank = 1820,
                xp = 12_400,
                isAnonymous = false,
                botSpeed = com.dangerfield.cards.libraries.cards.BotSpeed.Normal,
                turnFeedback = com.dangerfield.cards.libraries.cards.TurnFeedback.Vibrate,
                appVersion = "0.1.0",
                unreadNotificationCount = 5,
                showQaMenu = false,
            ),
            onClaimAccount = {},
            onEditProfile = {},
            onOpenMyItems = {},
            onOpenNotifications = {},
            onBotSpeedChange = {},
            onTurnFeedbackChange = {},
            onTapRank = {},
            onTapXp = {},
            onSendFeedback = {},
            onReportBug = {},
            onPrivacyPolicy = {},
            onTermsOfService = {},
            onDeleteAccount = {},
            onSignOut = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@androidx.compose.runtime.Composable
private fun ProfileScreenPreview_DebugBuild() {
    PreviewContent(bottomBar = PreviewBottomBar.Profile) {
        ProfileScreen(
            settings = ProfileSettings(
                displayName = "Elijah",
                avatarEmoji = "🦄",
                avatarBackgroundColor = "#7555ff",
                rank = 1820,
                xp = 12_400,
                isAnonymous = false,
                botSpeed = com.dangerfield.cards.libraries.cards.BotSpeed.Normal,
                turnFeedback = com.dangerfield.cards.libraries.cards.TurnFeedback.Mute,
                appVersion = "0.1.0-debug",
                showQaMenu = true,
            ),
            onClaimAccount = {},
            onEditProfile = {},
            onOpenMyItems = {},
            onOpenNotifications = {},
            onBotSpeedChange = {},
            onTurnFeedbackChange = {},
            onTapRank = {},
            onTapXp = {},
            onSendFeedback = {},
            onReportBug = {},
            onPrivacyPolicy = {},
            onTermsOfService = {},
            onDeleteAccount = {},
            onSignOut = {},
            onOpenQaMenu = {},
        )
    }
}
