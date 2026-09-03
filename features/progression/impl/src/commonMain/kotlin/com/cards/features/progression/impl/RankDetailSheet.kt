package com.dangerfield.cards.features.progression.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.rank_anon_subtitle
import cards.libraries.resources.generated.resources.rank_axes_note
import cards.libraries.resources.generated.resources.rank_bullet_bots
import cards.libraries.resources.generated.resources.rank_bullet_climb
import cards.libraries.resources.generated.resources.rank_bullet_elo
import cards.libraries.resources.generated.resources.rank_bullet_floor
import cards.libraries.resources.generated.resources.rank_claim_body
import cards.libraries.resources.generated.resources.rank_claim_cta
import cards.libraries.resources.generated.resources.rank_claim_title
import cards.libraries.resources.generated.resources.rank_claimed_subtitle
import cards.libraries.resources.generated.resources.rank_how_it_works_section
import cards.libraries.resources.generated.resources.rank_title
import cards.libraries.resources.generated.resources.rank_unranked
import cards.libraries.resources.generated.resources.rank_where_home
import cards.libraries.resources.generated.resources.rank_where_lobby
import cards.libraries.resources.generated.resources.rank_where_profile
import cards.libraries.resources.generated.resources.rank_where_seen_section
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.Surface
import com.dangerfield.cards.libraries.ui.components.header.TopBar
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.RankBadgeGradient
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD100
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview

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
                title = stringResource(Res.string.rank_title),
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

            SectionTitle(stringResource(Res.string.rank_how_it_works_section))
            Spacer(modifier = Modifier.height(8.dp))
            HowRankWorks()
            Spacer(modifier = Modifier.height(24.dp))

            if (state.isAnonymous) {
                ClaimAccountCard(onClick = onClaimAccount)
                Spacer(modifier = Modifier.height(8.dp))
            } else {
                SectionTitle(stringResource(Res.string.rank_where_seen_section))
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
                .background(RankBadgeGradient),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "♛",
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.content,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (rank <= 0) stringResource(Res.string.rank_unranked) else rank.toString(),
            typography = AppTheme.typography.Heading.H800,
            color = AppTheme.colors.content,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = if (isAnonymous) {
                stringResource(Res.string.rank_anon_subtitle)
            } else {
                stringResource(Res.string.rank_claimed_subtitle)
            },
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
        )
    }
}

@Composable
private fun HowRankWorks() {
    InfoCard {
        Bullet(stringResource(Res.string.rank_bullet_elo))
        Bullet(stringResource(Res.string.rank_bullet_bots))
        Bullet(stringResource(Res.string.rank_bullet_climb))
        Bullet(stringResource(Res.string.rank_bullet_floor))
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.rank_axes_note),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
        )
    }
}

@Composable
private fun WhereYouSeeIt() {
    InfoCard {
        Bullet(stringResource(Res.string.rank_where_home))
        Bullet(stringResource(Res.string.rank_where_profile))
        Bullet(stringResource(Res.string.rank_where_lobby))
    }
}

@Composable
private fun ClaimAccountCard(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AppTheme.colors.accentPrimary,
        contentColor = AppTheme.colors.content,
        radius = Radii.Card,
        onClick = onClick,
        bounceScale = 0.97f,
        contentPadding = PaddingValues(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(Res.string.rank_claim_title),
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.content,
            )
            Text(
                text = stringResource(Res.string.rank_claim_body),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.content,
            )
            VerticalSpacerD100()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Radii.R500.shape)
                    .background(AppTheme.colors.surfaceRaised.color)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.rank_claim_cta),
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.content,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        typography = AppTheme.typography.Body.B500,
        color = AppTheme.colors.content,
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
