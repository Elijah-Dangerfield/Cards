package com.dangerfield.cards.features.profile.impl.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import cards.libraries.resources.generated.resources.profile_about_privacy
import cards.libraries.resources.generated.resources.profile_about_terms
import cards.libraries.resources.generated.resources.profile_account_notifications_headline
import cards.libraries.resources.generated.resources.profile_account_notifications_supporting_default
import cards.libraries.resources.generated.resources.profile_account_notifications_supporting_unread
import cards.libraries.resources.generated.resources.profile_bot_speed_fast
import cards.libraries.resources.generated.resources.profile_bot_speed_normal
import cards.libraries.resources.generated.resources.profile_bot_speed_slow
import cards.libraries.resources.generated.resources.profile_debug_qa_headline
import cards.libraries.resources.generated.resources.profile_debug_qa_supporting
import cards.libraries.resources.generated.resources.profile_delete_account_button
import cards.libraries.resources.generated.resources.profile_gameplay_bot_speed_headline
import cards.libraries.resources.generated.resources.profile_gameplay_bot_speed_supporting
import cards.libraries.resources.generated.resources.profile_gameplay_turn_feedback_headline
import cards.libraries.resources.generated.resources.profile_gameplay_turn_feedback_supporting
import cards.libraries.resources.generated.resources.profile_gameplay_tutorial_headline
import cards.libraries.resources.generated.resources.profile_gameplay_tutorial_supporting
import cards.libraries.resources.generated.resources.profile_section_debug
import cards.libraries.resources.generated.resources.profile_section_gameplay
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
import cards.libraries.resources.generated.resources.settings_responsible_play_headline
import cards.libraries.resources.generated.resources.settings_responsible_play_supporting
import cards.libraries.resources.generated.resources.settings_section_account_support
import cards.libraries.resources.generated.resources.settings_title
import com.dangerfield.cards.features.profile.impl.ProfileSettings
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.BasicDropdownMenuItem
import com.dangerfield.cards.libraries.ui.components.BottomBarSpacer
import com.dangerfield.cards.libraries.ui.components.DropdownMenu
import com.dangerfield.cards.libraries.ui.components.ListItemAccessory
import com.dangerfield.cards.libraries.ui.components.ListSection
import com.dangerfield.cards.libraries.ui.components.ListSectionItem
import com.dangerfield.cards.libraries.ui.components.SaveProgressBanner
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.StatusPill
import com.dangerfield.cards.libraries.ui.components.button.ButtonDanger
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.header.TopBar
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD800
import com.dangerfield.cards.system.VerticalSpacerD1100
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * App settings — the gear destination from the Profile. Holds everything
 * that used to be crammed onto the Profile tab when Profile *was* the
 * settings screen: the gameplay toggles, notifications, support/about
 * links, and the account actions. Rows carry leading emoji icons and a
 * redesigned "save your progress" sign-in banner sits up top for guests.
 *
 * Takes [ProfileSettings] (the same shared snapshot the Profile uses) plus
 * the settings callbacks; the EntryPoint wires both screens off the same
 * repositories.
 */
@Composable
fun SettingsScreen(
    settings: ProfileSettings,
    onBack: () -> Unit,
    onClaimAccount: () -> Unit,
    onOpenNotifications: () -> Unit,
    onBotSpeedChange: (com.dangerfield.cards.libraries.cards.BotSpeed) -> Unit,
    onTurnFeedbackChange: (com.dangerfield.cards.libraries.cards.TurnFeedback) -> Unit,
    onSendFeedback: () -> Unit,
    onReportBug: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onTermsOfService: () -> Unit,
    onResponsiblePlay: () -> Unit,
    onDeleteAccount: () -> Unit,
    onSignOut: () -> Unit,
    isSigningOut: Boolean = false,
    onOpenQaMenu: () -> Unit = {},
    onOpenTutorial: () -> Unit = {},
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    var showSignOutDialog by remember { mutableStateOf(false) }
    Screen(
        modifier = modifier,
        topBar = {
            TopBar(
                title = stringResource(Res.string.settings_title),
                onNavigateBack = onBack,
                scrollState = scrollState,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .screenContentPadding(paddingValues = padding)
                .padding(vertical = 16.dp),
        ) {
            if (settings.isAnonymous) {
                SaveProgressBanner(onSignIn = onClaimAccount)
                VerticalSpacerD800()
            }

            GameplaySection(
                botSpeed = settings.botSpeed,
                turnFeedback = settings.turnFeedback,
                onBotSpeedChange = onBotSpeedChange,
                onTurnFeedbackChange = onTurnFeedbackChange,
                onOpenTutorial = onOpenTutorial,
            )

            VerticalSpacerD800()

            ListSection(
                title = stringResource(Res.string.settings_section_account_support),
                items = listOfNotNull(
                    ListSectionItem(
                        headlineText = stringResource(Res.string.profile_account_notifications_headline),
                        supportingText = stringResource(Res.string.profile_account_notifications_supporting_default),
                        leadingContent = { EmojiLeading("🔔") },
                        accessory = if (settings.unreadNotificationCount > 0) {
                            val chipText = stringResource(
                                Res.string.profile_account_notifications_supporting_unread,
                                settings.unreadNotificationCount,
                            )
                            ListItemAccessory.Custom { UnreadNotificationsChip(text = chipText) }
                        } else {
                            ListItemAccessory.Chevron
                        },
                        onClick = onOpenNotifications,
                    ),
                    ListSectionItem(
                        headlineText = stringResource(Res.string.profile_support_feedback_headline),
                        supportingText = stringResource(Res.string.profile_support_feedback_supporting),
                        leadingContent = { EmojiLeading("💬") },
                        onClick = onSendFeedback,
                    ),
                    ListSectionItem(
                        headlineText = stringResource(Res.string.profile_support_bug_headline),
                        supportingText = stringResource(Res.string.profile_support_bug_supporting),
                        leadingContent = { EmojiLeading("🐞") },
                        onClick = onReportBug,
                    ),
                    ListSectionItem(
                        headlineText = stringResource(Res.string.profile_about_privacy),
                        leadingContent = { EmojiLeading("🛡️") },
                        onClick = onPrivacyPolicy,
                    ),
                    ListSectionItem(
                        headlineText = stringResource(Res.string.profile_about_terms),
                        leadingContent = { EmojiLeading("📄") },
                        onClick = onTermsOfService,
                    ),
                    ListSectionItem(
                        headlineText = stringResource(Res.string.settings_responsible_play_headline),
                        supportingText = stringResource(Res.string.settings_responsible_play_supporting),
                        leadingContent = { EmojiLeading("🤝") },
                        onClick = onResponsiblePlay,
                    ),
                ),
            )

            if (settings.showQaMenu) {
                VerticalSpacerD800()
                ListSection(
                    title = stringResource(Res.string.profile_section_debug),
                    items = listOf(
                        ListSectionItem(
                            headlineText = stringResource(Res.string.profile_debug_qa_headline),
                            supportingText = stringResource(Res.string.profile_debug_qa_supporting),
                            leadingContent = { EmojiLeading("🛠️") },
                            onClick = onOpenQaMenu,
                        ),
                    ),
                )
            }

            VerticalSpacerD1100()
            // Anonymous "sign out" is meaningless — there's no account to sign
            // back into. Guests get the sign-in banner above instead.
            if (!settings.isAnonymous) {
                ButtonDanger(
                    onClick = { if (!isSigningOut) showSignOutDialog = true },
                    style = ButtonStyle.Outlined,
                    enabled = !isSigningOut,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (isSigningOut) {
                            stringResource(Res.string.profile_sign_out_button_progress)
                        } else {
                            stringResource(Res.string.profile_sign_out_button)
                        },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            // Deletion is available to everyone, including guests — a guest's
            // data is a real account they have the right to erase.
            ButtonDanger(
                onClick = onDeleteAccount,
                style = ButtonStyle.Text,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.profile_delete_account_button))
            }

            VerticalSpacerD1100()
            // App version stands in for the old "About Cards" row + legal
            // footer — a quiet build stamp at the foot of Settings.
            Text(
                text = settings.appVersion,
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.contentSecondary,
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
private fun EmojiLeading(emoji: String) {
    Text(text = emoji, typography = AppTheme.typography.Body.B700)
}


@Composable
private fun UnreadNotificationsChip(text: String) {
    StatusPill(
        text = text,
        background = AppTheme.colors.accentPrimary,
        foreground = AppTheme.colors.onAccentPrimary,
    )
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
                color = AppTheme.colors.content,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = body,
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
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
                style = ButtonStyle.Text,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.profile_sign_out_dialog_cancel_button))
            }
        }
    }
}

// ---- Gameplay section (moved from ProfileScreen) ----------------------

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
                leadingContent = { EmojiLeading("🃏") },
                onClick = onOpenTutorial,
            ),
            ListSectionItem(
                headlineText = stringResource(Res.string.profile_gameplay_bot_speed_headline),
                supportingText = stringResource(Res.string.profile_gameplay_bot_speed_supporting),
                leadingContent = { EmojiLeading("⚡") },
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
                leadingContent = { EmojiLeading("🔊") },
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
            color = AppTheme.colors.contentSecondary,
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
}@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun SettingsScreenPreview_Guest() {
    PreviewContent {
        SettingsScreen(
            settings = ProfileSettings(
                displayName = "Anon-1742",
                avatarEmoji = "🦊",
                avatarBackgroundColor = null,
                rank = 0,
                xp = 340,
                isAnonymous = true,
                botSpeed = com.dangerfield.cards.libraries.cards.BotSpeed.Normal,
                turnFeedback = com.dangerfield.cards.libraries.cards.TurnFeedback.Vibrate,
                appVersion = "0.1.0",
                unreadNotificationCount = 2,
                showQaMenu = false,
            ),
            onBack = {},
            onClaimAccount = {},
            onOpenNotifications = {},
            onBotSpeedChange = {},
            onTurnFeedbackChange = {},
            onSendFeedback = {},
            onReportBug = {},
            onPrivacyPolicy = {},
            onTermsOfService = {},
            onResponsiblePlay = {},
            onDeleteAccount = {},
            onSignOut = {},
        )
    }
}
