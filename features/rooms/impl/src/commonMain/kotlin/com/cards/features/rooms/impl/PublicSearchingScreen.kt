package com.dangerfield.cards.features.rooms.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.public_searching_blinds
import cards.libraries.resources.generated.resources.public_searching_body
import cards.libraries.resources.generated.resources.public_searching_buyin_label
import cards.libraries.resources.generated.resources.public_searching_buyin_value
import cards.libraries.resources.generated.resources.public_searching_cancel
import cards.libraries.resources.generated.resources.public_searching_found
import cards.libraries.resources.generated.resources.public_searching_heading
import cards.libraries.resources.generated.resources.public_searching_title
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.isReduceMotionEnabled
import com.dangerfield.cards.libraries.ui.components.ChipCoin
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.room.MatchmakingRadar
import com.dangerfield.cards.libraries.ui.components.room.RoomHeader
import com.dangerfield.cards.libraries.ui.components.room.RoomVisibility
import com.dangerfield.cards.libraries.ui.components.room.SeatAvatar
import com.dangerfield.cards.libraries.ui.components.room.SeatStack
import com.dangerfield.cards.libraries.ui.components.room.VisTag
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * Public Searching / matchmaking screen (SPEC §2) — the radar, "holding seats"
 * copy, and a real Cancel hatch. This is a visual shell: there's no live
 * matchmaking, so after a brief, clearly-cosmetic beat it advances to the
 * (static) public Lobby so the rest of the flow is reviewable. Cancel returns
 * to Find.
 */
@Composable
fun PublicSearchingScreen(
    onCancel: () -> Unit,
    onMatched: () -> Unit,
) {
    // Cosmetic only — no real match. Lets a reviewer see the radar animate,
    // then walk on to the Lobby shell.
    LaunchedEffect(Unit) {
        delay(3_000)
        onMatched()
    }

    Screen(
        topBar = {
            RoomHeader(
                title = stringResource(Res.string.public_searching_title),
                onNavigateBack = onCancel,
                right = { VisTag(kind = RoomVisibility.Public) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .screenContentPadding(paddingValues = padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))

            MatchmakingRadar(reduceMotion = isReduceMotionEnabled())

            Spacer(Modifier.height(Dimension.D900))
            Text(
                text = stringResource(Res.string.public_searching_heading) + " …",
                typography = AppTheme.typography.Heading.H800,
                color = AppTheme.colors.content,
            )
            Spacer(Modifier.height(Dimension.D300))
            Text(
                text = stringResource(Res.string.public_searching_body),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(Dimension.D850))
            SeatStack(
                seats = listOf(
                    SeatAvatar("Marisol", "🦁", "#C658E4"),
                    SeatAvatar("Theo", "🐯", "#E48A58"),
                    SeatAvatar("You", "🦊", "#5894E4"),
                ),
                empty = 3,
                size = 36.dp,
            )
            Spacer(Modifier.height(Dimension.D500))
            Text(
                text = stringResource(Res.string.public_searching_found, 3, 3),
                typography = AppTheme.typography.Label.L400,
                color = AppTheme.colors.accentSecondary,
            )

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
                ChipCoin(size = 18.dp)
                Spacer(Modifier.size(Dimension.D400))
                Column(modifier = Modifier.weight(1f)) {
                    Eyebrow(stringResource(Res.string.public_searching_buyin_label))
                    Text(
                        text = stringResource(Res.string.public_searching_buyin_value),
                        typography = AppTheme.typography.Label.L500,
                        color = AppTheme.colors.content,
                    )
                }
                Text(
                    text = stringResource(Res.string.public_searching_blinds),
                    typography = AppTheme.typography.Label.L400,
                    color = AppTheme.colors.contentTertiary,
                )
            }
            Spacer(Modifier.height(Dimension.D500))
            ButtonSecondary(
                onClick = onCancel,
                style = ButtonStyle.Outlined,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.public_searching_cancel))
            }
            Spacer(Modifier.height(Dimension.D800))
        }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun PublicSearchingScreenPreview() {
    PreviewContent {
        PublicSearchingScreen(onCancel = {}, onMatched = {})
    }
}
