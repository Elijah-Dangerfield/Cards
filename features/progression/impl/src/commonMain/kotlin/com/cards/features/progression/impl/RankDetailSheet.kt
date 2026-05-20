package com.dangerfield.cards.features.progression.impl

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.header.TopBar
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun RankDetailSheet(
    state: RankDetailState,
    onBack: () -> Unit,
    onClaimAccount: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Screen(
        topBar = {
            TopBar(
                title = "Rank",
                onNavigateBack = onBack,
                scrollState = scrollState,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(scrollState),
        ) {
            RankHero(rank = state.rank, isAnonymous = state.isAnonymous)
            Spacer(modifier = Modifier.height(24.dp))

            SectionTitle("How rank works")
            Spacer(modifier = Modifier.height(8.dp))
            HowRankWorks()
            Spacer(modifier = Modifier.height(24.dp))

            if (state.isAnonymous) {
                ClaimAccountCard(onClick = onClaimAccount)
                Spacer(modifier = Modifier.height(8.dp))
            } else {
                SectionTitle("Where you'll see it")
                Spacer(modifier = Modifier.height(8.dp))
                WhereYouSeeIt()
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun RankHero(rank: Int, isAnonymous: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF8E7CC3), Color(0xFFE07AB1)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "♛",
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.text,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (rank <= 0) "Unranked" else rank.toString(),
            typography = AppTheme.typography.Heading.H800,
            color = AppTheme.colors.text,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = if (isAnonymous) "Claim your account to start ranking" else "Multiplayer Elo rating",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun HowRankWorks() {
    InfoCard {
        Bullet("Rank is your Elo skill rating against real opponents")
        Bullet("Bots don't change your rank — only multiplayer hands do")
        Bullet("Win against stronger players to climb faster")
        Bullet("Rank can't drop below 800 — no need to dread a bad night")
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Three separate axes — chips, rank, XP — so a rough session never wipes your sense of progress.",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun WhereYouSeeIt() {
    InfoCard {
        Bullet("Top of the home screen, next to your XP and chips")
        Bullet("On the profile screen under \"Account\"")
        Bullet("In multiplayer lobbies, next to your handle")
    }
}

@Composable
private fun ClaimAccountCard(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AppTheme.colors.accentPrimary.color)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Play with real opponents",
            typography = AppTheme.typography.Body.B600,
            color = AppTheme.colors.text,
        )
        Text(
            text = "Sign in with Apple or Google to unlock multiplayer, earn a real rank, and save your chips across devices. Your XP and progress carry over.",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.text,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.18f))
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Claim your account",
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.text,
            )
        }
    }
}

@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AppTheme.colors.surfaceSecondary.color)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

@Composable
private fun Bullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = "·",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.textSecondary,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.text,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        typography = AppTheme.typography.Body.B500,
        color = AppTheme.colors.text,
    )
}

@Preview
@Composable
private fun RankDetailSheet_Anonymous() {
    PreviewContent {
        RankDetailSheet(
            state = RankDetailState(isAnonymous = true, rank = 0),
            onBack = {},
            onClaimAccount = {},
        )
    }
}

@Preview
@Composable
private fun RankDetailSheet_Claimed() {
    PreviewContent {
        RankDetailSheet(
            state = RankDetailState(isAnonymous = false, rank = 1200),
            onBack = {},
            onClaimAccount = {},
        )
    }
}
