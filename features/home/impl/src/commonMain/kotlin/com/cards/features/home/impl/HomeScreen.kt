package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme

private val ChipGold = Color(0xFFE0B863)
private val CardCasual = Color(0xFF2D5F4A)
private val CardStandard = Color(0xFF2D4A6F)
private val CardChallenging = Color(0xFF6F2D4A)

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToFeedback: () -> Unit,
    onNavigateToBugReport: () -> Unit,
    onPlayBots: (difficulty: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()

    Screen(modifier = modifier) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            TopStatusBar(
                rank = 1200,
                cashBalance = 10_000,
            )
            Spacer(modifier = Modifier.height(28.dp))
            HeroChips(amount = 10_000)
            Spacer(modifier = Modifier.height(28.dp))
            SectionLabel("Practice against bots")
            Spacer(modifier = Modifier.height(12.dp))
            PlayCard(
                title = "Casual table",
                subtitle = "Forgiving bots · learning mode",
                accent = CardCasual,
                onClick = { onPlayBots("Casual") },
            )
            Spacer(modifier = Modifier.height(12.dp))
            PlayCard(
                title = "Standard table",
                subtitle = "Balanced bots · most realistic",
                accent = CardStandard,
                onClick = { onPlayBots("Standard") },
            )
            Spacer(modifier = Modifier.height(12.dp))
            PlayCard(
                title = "Challenging table",
                subtitle = "Aggressive bots · they read you",
                accent = CardChallenging,
                onClick = { onPlayBots("Challenging") },
            )
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun TopStatusBar(rank: Int, cashBalance: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RankBadge(rank = rank)
        Spacer(modifier = Modifier.weight(1f))
        CashBadge(amount = cashBalance)
    }
}

@Composable
private fun RankBadge(rank: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
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
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.text,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Rank $rank",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.text,
        )
    }
}

@Composable
private fun CashBadge(amount: Long) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(ChipGold),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.background,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = formatThousands(amount),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.text,
        )
    }
}

@Composable
private fun HeroChips(amount: Long) {
    Column {
        Text(
            text = "Your chips",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.textSecondary,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = formatThousands(amount),
            typography = AppTheme.typography.Heading.H800,
            color = AppTheme.colors.text,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        typography = AppTheme.typography.Body.B500,
        color = AppTheme.colors.text,
    )
}

@Composable
private fun PlayCard(
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.7f)),
                ),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "♠",
                typography = AppTheme.typography.Heading.H600,
                color = AppTheme.colors.text,
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.text,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.text,
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = AppTheme.colors.text.color,
        )
    }
}

private fun formatThousands(value: Long): String {
    val s = value.toString()
    val withCommas = StringBuilder()
    val len = s.length
    for (i in 0 until len) {
        if (i > 0 && (len - i) % 3 == 0) withCommas.append(',')
        withCommas.append(s[i])
    }
    return withCommas.toString()
}
