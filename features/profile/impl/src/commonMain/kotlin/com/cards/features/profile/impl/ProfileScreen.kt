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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.ListSection
import com.dangerfield.cards.libraries.ui.components.ListSectionItem
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme

data class ProfileSettings(
    val displayName: String,
    val handle: String,
    val rank: Int,
    val xp: Long,
    val handsPlayed: Long,
    val isAnonymous: Boolean,
    val gameplaySpeed: GameplaySpeed,
    val appVersion: String,
    val showQaMenu: Boolean = false,
)

enum class GameplaySpeed(val label: String) {
    Slow("Slow"),
    Normal("Normal"),
    Fast("Fast"),
}

@Composable
fun ProfileScreen(
    settings: ProfileSettings,
    onClaimAccount: () -> Unit,
    onEditProfile: () -> Unit,
    onChangeGameplaySpeed: () -> Unit,
    onTapRank: () -> Unit,
    onTapXp: () -> Unit,
    onSendFeedback: () -> Unit,
    onReportBug: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onTermsOfService: () -> Unit,
    onDeleteAccount: () -> Unit,
    onOpenQaMenu: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Screen(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            ProfileHeader(settings = settings)
            Spacer(modifier = Modifier.height(24.dp))

            if (settings.isAnonymous) {
                ClaimAccountCard(onClaimAccount = onClaimAccount)
                Spacer(modifier = Modifier.height(20.dp))
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
                    ListSectionItem(
                        headlineText = "Hands played",
                        accessory = com.dangerfield.cards.libraries.ui.components.ListItemAccessory.Text(
                            text = settings.handsPlayed.toString(),
                        ),
                    ),
                ),
            )

            Spacer(modifier = Modifier.height(20.dp))

            ListSection(
                title = "Gameplay",
                items = listOf(
                    ListSectionItem(
                        headlineText = "Speed",
                        supportingText = "How fast cards and chips move",
                        accessory = com.dangerfield.cards.libraries.ui.components.ListItemAccessory.Text(
                            text = settings.gameplaySpeed.label,
                        ),
                        onClick = onChangeGameplaySpeed,
                    ),
                ),
            )

            Spacer(modifier = Modifier.height(20.dp))

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

            Spacer(modifier = Modifier.height(20.dp))

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

            if (!settings.isAnonymous) {
                Spacer(modifier = Modifier.height(20.dp))
                ListSection(
                    items = listOf(
                        ListSectionItem(
                            headlineText = "Delete account",
                            onClick = onDeleteAccount,
                        ),
                    ),
                )
            }

            if (settings.showQaMenu) {
                Spacer(modifier = Modifier.height(20.dp))
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

            Spacer(modifier = Modifier.height(32.dp))
            // App version as a quiet, centered footer rather than a table cell.
            // It's a "where am I in the release cycle" reference, not an action.
            Text(
                text = "v${settings.appVersion}",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(24.dp))
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
            size = 96.dp,
            typography = AppTheme.typography.Heading.H800,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = settings.displayName,
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.text,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = if (settings.isAnonymous) "Guest · ${settings.handle}" else "@${settings.handle}",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.textSecondary,
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
        Spacer(modifier = Modifier.height(4.dp))
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
                handle = "anon-1742",
                rank = 1200,
                xp = 60,
                handsPlayed = 0,
                isAnonymous = true,
                gameplaySpeed = GameplaySpeed.Normal,
                appVersion = "0.1.0",
                showQaMenu = false,
            ),
            onClaimAccount = {},
            onEditProfile = {},
            onChangeGameplaySpeed = {},
            onTapRank = {},
            onTapXp = {},
            onSendFeedback = {},
            onReportBug = {},
            onPrivacyPolicy = {},
            onTermsOfService = {},
            onDeleteAccount = {},
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
                handle = "edangerfield",
                rank = 1820,
                xp = 12_400,
                handsPlayed = 412,
                isAnonymous = false,
                gameplaySpeed = GameplaySpeed.Fast,
                appVersion = "0.1.0",
                showQaMenu = false,
            ),
            onClaimAccount = {},
            onEditProfile = {},
            onChangeGameplaySpeed = {},
            onTapRank = {},
            onTapXp = {},
            onSendFeedback = {},
            onReportBug = {},
            onPrivacyPolicy = {},
            onTermsOfService = {},
            onDeleteAccount = {},
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
                handle = "edangerfield",
                rank = 1820,
                xp = 12_400,
                handsPlayed = 412,
                isAnonymous = false,
                gameplaySpeed = GameplaySpeed.Normal,
                appVersion = "0.1.0-debug",
                showQaMenu = true,
            ),
            onClaimAccount = {},
            onEditProfile = {},
            onChangeGameplaySpeed = {},
            onTapRank = {},
            onTapXp = {},
            onSendFeedback = {},
            onReportBug = {},
            onPrivacyPolicy = {},
            onTermsOfService = {},
            onDeleteAccount = {},
            onOpenQaMenu = {},
        )
    }
}
