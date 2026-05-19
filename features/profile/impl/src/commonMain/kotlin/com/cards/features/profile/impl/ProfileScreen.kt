package com.dangerfield.cards.features.profile.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.BasicDropdownMenuItem
import com.dangerfield.cards.libraries.ui.components.BottomBarSpacer
import com.dangerfield.cards.libraries.ui.components.DropdownMenu
import com.dangerfield.cards.libraries.ui.components.ListItemAccessory
import com.dangerfield.cards.libraries.ui.components.ListSection
import com.dangerfield.cards.libraries.ui.components.ListSectionItem
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.VerticalSpacerD100
import com.dangerfield.cards.system.VerticalSpacerD1100
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD800
import com.dangerfield.cards.system.VerticalSpacerD900

data class ProfileSettings(
    val displayName: String,
    val avatarEmoji: String?,
    val rank: Int,
    val xp: Long,
    val isAnonymous: Boolean,
    val botSpeed: com.dangerfield.cards.libraries.cards.BotSpeed,
    val turnFeedback: com.dangerfield.cards.libraries.cards.TurnFeedback,
    val appVersion: String,
    val showQaMenu: Boolean = false,
)

@Composable
fun ProfileScreen(
    settings: ProfileSettings,
    onClaimAccount: () -> Unit,
    onEditProfile: () -> Unit,
    onOpenMyItems: () -> Unit,
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
    modifier: Modifier = Modifier,
) {
    var showSignOutDialog by remember { mutableStateOf(false) }
    Screen(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            ProfileHeader(settings = settings)
            VerticalSpacerD900()

            if (settings.isAnonymous) {
                ClaimAccountCard(onClaimAccount = onClaimAccount)
                VerticalSpacerD800()
            }

            ListSection(
                title = "Account",
                items = listOf(
                    ListSectionItem(
                        headlineText = "Edit profile",
                        supportingText = "Name and avatar",
                        onClick = onEditProfile,
                    ),
                    ListSectionItem(
                        headlineText = "My items",
                        supportingText = "Owned card backs, felts, emotes, titles",
                        onClick = onOpenMyItems,
                    ),
                    ListSectionItem(
                        headlineText = "Rank",
                        supportingText = "Skill rating · multiplayer only",
                        accessory = com.dangerfield.cards.libraries.ui.components.ListItemAccessory.Text(
                            text = if (settings.rank <= 0) "Unranked" else settings.rank.toString(),
                        ),
                        onClick = onTapRank,
                    ),
                    ListSectionItem(
                        headlineText = "XP",
                        supportingText = "Lifetime · bots earn at 0.5×",
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
            )

            VerticalSpacerD800()

            ListSection(
                title = "Support",
                items = listOf(
                    ListSectionItem(
                        headlineText = "Send feedback",
                        supportingText = "Tell us what's working and what isn't",
                        onClick = onSendFeedback,
                    ),
                    ListSectionItem(
                        headlineText = "Report a bug",
                        supportingText = "Something broken? Let us know",
                        onClick = onReportBug,
                    ),
                ),
            )

            VerticalSpacerD800()

            ListSection(
                title = "About",
                items = listOf(
                    ListSectionItem(
                        headlineText = "Privacy policy",
                        onClick = onPrivacyPolicy,
                    ),
                    ListSectionItem(
                        headlineText = "Terms of service",
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
                    Text(if (isSigningOut) "Signing out…" else "Sign out")
                }
                Spacer(modifier = Modifier.height(8.dp))
                com.dangerfield.cards.libraries.ui.components.button.ButtonDanger(
                    onClick = onDeleteAccount,
                    style = com.dangerfield.cards.libraries.ui.components.button.ButtonStyle.Text,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Delete account")
                }
            }

            if (settings.showQaMenu) {
                VerticalSpacerD800()
                ListSection(
                    title = "Debug",
                    items = listOf(
                        ListSectionItem(
                            headlineText = "QA menu",
                            supportingText = "Override config values for this session",
                            onClick = onOpenQaMenu,
                        ),
                    ),
                )
            }

            VerticalSpacerD1100()
            // App version as a quiet, centered footer rather than a table cell.
            // It's a "where am I in the release cycle" reference, not an action.
            Text(
                text = "v${settings.appVersion}",
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
    val title = if (isAnonymous) "Sign out of guest account?" else "Sign out?"
    val body = if (isAnonymous) {
        "Your chips, XP, and game history are tied to this guest account. Signing out drops them. To keep your progress, choose 'Claim your account' instead."
    } else {
        "You'll be returned to the welcome screen. Your account stays — sign in again any time to come back."
    }
    com.dangerfield.cards.libraries.ui.components.dialog.Dialog(onDismissRequest = onDismiss) {
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
                Text("Sign out")
            }
            Spacer(modifier = Modifier.height(8.dp))
            com.dangerfield.cards.libraries.ui.components.button.Button(
                onClick = onDismiss,
                style = com.dangerfield.cards.libraries.ui.components.button.ButtonStyle.Text,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun GameplaySection(
    botSpeed: com.dangerfield.cards.libraries.cards.BotSpeed,
    turnFeedback: com.dangerfield.cards.libraries.cards.TurnFeedback,
    onBotSpeedChange: (com.dangerfield.cards.libraries.cards.BotSpeed) -> Unit,
    onTurnFeedbackChange: (com.dangerfield.cards.libraries.cards.TurnFeedback) -> Unit,
) {
    var botSpeedExpanded by remember { mutableStateOf(false) }
    var turnFeedbackExpanded by remember { mutableStateOf(false) }

    ListSection(
        title = "Gameplay",
        items = listOf(
            ListSectionItem(
                headlineText = "Bot speed",
                supportingText = "How fast the bots think and act",
                accessory = ListItemAccessory.Custom {
                    DropdownAccessory(
                        text = botSpeed.label,
                        expanded = botSpeedExpanded,
                        onDismiss = { botSpeedExpanded = false },
                        options = com.dangerfield.cards.libraries.cards.BotSpeed.entries.toList(),
                        label = { it.label },
                        onSelect = {
                            botSpeedExpanded = false
                            onBotSpeedChange(it)
                        },
                    )
                },
                onClick = { botSpeedExpanded = true },
            ),
            ListSectionItem(
                headlineText = "Your turn feedback",
                supportingText = "Cue when it becomes your turn",
                accessory = ListItemAccessory.Custom {
                    DropdownAccessory(
                        text = turnFeedback.label,
                        expanded = turnFeedbackExpanded,
                        onDismiss = { turnFeedbackExpanded = false },
                        options = com.dangerfield.cards.libraries.cards.TurnFeedback.entries.toList(),
                        label = { it.label },
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

@Composable
private fun <T> DropdownAccessory(
    text: String,
    expanded: Boolean,
    onDismiss: () -> Unit,
    options: List<T>,
    label: (T) -> String,
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
private fun ProfileHeader(settings: ProfileSettings) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
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
        )
        VerticalSpacerD500()
        Text(
            text = settings.displayName,
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.text,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ClaimAccountCard(onClaimAccount: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .background(AppTheme.colors.accentPrimary.color)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Claim your account",
            typography = AppTheme.typography.Body.B600,
            color = AppTheme.colors.text,
        )
        Text(
            text = "Save your chips and unlock leaderboards. Sign in with Apple or Google in seconds.",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.text,
        )
        VerticalSpacerD100()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.15f))
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Get started",
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.text,
            )
        }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@androidx.compose.runtime.Composable
private fun ProfileScreenPreview_Anonymous() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        ProfileScreen(
            settings = ProfileSettings(
                displayName = "Anon-1742",
                avatarEmoji = "🦊",
                rank = 1200,
                xp = 60,
                isAnonymous = true,
                botSpeed = com.dangerfield.cards.libraries.cards.BotSpeed.Normal,
                turnFeedback = com.dangerfield.cards.libraries.cards.TurnFeedback.Sound,
                appVersion = "0.1.0",
                showQaMenu = false,
            ),
            onClaimAccount = {},
            onEditProfile = {},
            onOpenMyItems = {},
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
    com.dangerfield.cards.libraries.ui.PreviewContent {
        ProfileScreen(
            settings = ProfileSettings(
                displayName = "Elijah",
                avatarEmoji = "🦄",
                rank = 1820,
                xp = 12_400,
                isAnonymous = false,
                botSpeed = com.dangerfield.cards.libraries.cards.BotSpeed.Fast,
                turnFeedback = com.dangerfield.cards.libraries.cards.TurnFeedback.Vibrate,
                appVersion = "0.1.0",
                showQaMenu = false,
            ),
            onClaimAccount = {},
            onEditProfile = {},
            onOpenMyItems = {},
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
    com.dangerfield.cards.libraries.ui.PreviewContent {
        ProfileScreen(
            settings = ProfileSettings(
                displayName = "Elijah",
                avatarEmoji = "🦄",
                rank = 1820,
                xp = 12_400,
                isAnonymous = false,
                botSpeed = com.dangerfield.cards.libraries.cards.BotSpeed.Normal,
                turnFeedback = com.dangerfield.cards.libraries.cards.TurnFeedback.Sound,
                appVersion = "0.1.0-debug",
                showQaMenu = true,
            ),
            onClaimAccount = {},
            onEditProfile = {},
            onOpenMyItems = {},
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
