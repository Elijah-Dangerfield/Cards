package com.dangerfield.cards.features.rooms.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.public_leave_table
import cards.libraries.resources.generated.resources.public_lobby_subtitle
import cards.libraries.resources.generated.resources.public_lobby_title
import cards.libraries.resources.generated.resources.public_next_at_table
import cards.libraries.resources.generated.resources.public_next_buyin_label
import cards.libraries.resources.generated.resources.public_next_buyin_note
import cards.libraries.resources.generated.resources.public_next_buyin_value
import cards.libraries.resources.generated.resources.public_next_card_body
import cards.libraries.resources.generated.resources.public_next_card_title
import cards.libraries.resources.generated.resources.public_next_change
import cards.libraries.resources.generated.resources.public_next_in_progress
import cards.libraries.resources.generated.resources.public_next_joining
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.ChipCoin
import com.dangerfield.cards.libraries.ui.components.NonLazyVerticalGrid
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.room.RoomHeader
import com.dangerfield.cards.libraries.ui.components.room.RoomSeat
import com.dangerfield.cards.libraries.ui.components.room.RoomVisibility
import com.dangerfield.cards.libraries.ui.components.room.VisTag
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.resources.stringResource

/**
 * Public NextRound shell (SPEC §4) — matched into a table mid-hand; you watch
 * the current hand and get dealt in next round at your buy-in. Static
 * placeholder seats (you = up next). "Leave table" exits.
 */
@Composable
fun PublicNextRoundScreen(
    onLeave: () -> Unit,
) {
    val seats = nextRoundSeats()
    Screen(
        topBar = {
            RoomHeader(
                title = stringResource(Res.string.public_lobby_title),
                sub = stringResource(Res.string.public_lobby_subtitle),
                onNavigateBack = onLeave,
                right = { VisTag(kind = RoomVisibility.Public) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .screenContentPadding(paddingValues = padding),
        ) {
            Spacer(Modifier.height(Dimension.D500))

            PublicHeroCard(
                title = stringResource(Res.string.public_next_card_title),
                body = stringResource(Res.string.public_next_card_body),
                leading = { HeroBadge { Text("♣", typography = AppTheme.typography.Heading.H700, color = AppTheme.colors.content) } },
            )

            Spacer(Modifier.height(Dimension.D800))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(Res.string.public_next_at_table),
                    typography = AppTheme.typography.Heading.H700,
                    color = AppTheme.colors.content,
                )
                LiveTag()
            }
            Spacer(Modifier.height(Dimension.D400))
            NonLazyVerticalGrid(
                columns = 3,
                data = seats,
                horizontalSpacing = Dimension.D500,
                verticalSpacing = Dimension.D500,
                modifier = Modifier.fillMaxWidth(),
            ) { _, player ->
                RoomSeat(player = player)
            }

            Spacer(Modifier.height(Dimension.D700))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Radii.R700.shape)
                    .background(AppTheme.colors.surface.color)
                    .border(1.dp, AppTheme.colors.border.color, Radii.R700.shape)
                    .padding(horizontal = Dimension.D500, vertical = Dimension.D500),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChipCoin(size = 20.dp)
                Spacer(Modifier.size(Dimension.D400))
                Column(modifier = Modifier.weight(1f)) {
                    Eyebrow(stringResource(Res.string.public_next_buyin_label))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(Res.string.public_next_buyin_value),
                            typography = AppTheme.typography.Heading.H700,
                            color = AppTheme.colors.content,
                        )
                        Spacer(Modifier.size(Dimension.D300))
                        Text(
                            text = "· " + stringResource(Res.string.public_next_buyin_note),
                            typography = AppTheme.typography.Body.B400,
                            color = AppTheme.colors.contentTertiary,
                        )
                    }
                }
                Text(
                    text = stringResource(Res.string.public_next_change),
                    typography = AppTheme.typography.Label.L400,
                    color = AppTheme.colors.accentPrimary,
                )
            }

            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Radii.R700.shape)
                    .background(AppTheme.colors.surface.color)
                    .border(1.dp, AppTheme.colors.border.color, Radii.R700.shape)
                    .padding(horizontal = Dimension.D500, vertical = Dimension.D500),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(AppTheme.colors.accentPrimary.color),
                )
                Spacer(Modifier.size(Dimension.D400))
                Text(
                    text = stringResource(Res.string.public_next_joining),
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.contentSecondary,
                )
            }
            Spacer(Modifier.height(Dimension.D200))
            ButtonSecondary(
                onClick = onLeave,
                style = ButtonStyle.Text,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.public_leave_table))
            }
            Spacer(Modifier.height(Dimension.D800))
        }
    }
}

@Composable
private fun LiveTag() {
    Row(
        modifier = Modifier
            .clip(Radii.Round.shape)
            .background(AppTheme.colors.successSubtle.color)
            .padding(horizontal = Dimension.D400, vertical = Dimension.D200),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(AppTheme.colors.success.color),
        )
        Spacer(Modifier.size(Dimension.D200))
        Text(
            text = stringResource(Res.string.public_next_in_progress),
            typography = AppTheme.typography.Label.L400,
            color = AppTheme.colors.success,
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun PublicNextRoundScreenPreview() {
    PreviewContent {
        PublicNextRoundScreen(onLeave = {})
    }
}
