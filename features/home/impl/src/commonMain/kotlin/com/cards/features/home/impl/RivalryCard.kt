package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.home_rivalry_ahead
import cards.libraries.resources.generated.resources.home_rivalry_behind
import cards.libraries.resources.generated.resources.home_rivalry_biggest_pot
import cards.libraries.resources.generated.resources.home_rivalry_even
import cards.libraries.resources.generated.resources.home_rivalry_rematch_button
import cards.libraries.resources.generated.resources.home_rivalry_sessions
import com.dangerfield.cards.libraries.social.Rivalry
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.formatCompactChips
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.HorizontalSpacerD400
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD500
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * The standing against someone you keep playing, and a tap to set up the next
 * one.
 *
 * Leads with the scoreline rather than the name, because the scoreline is the
 * part that is unfinished. Losing reads as "Mark is up 14 hands to 11", which is
 * the version of this card that does any work: a daily bonus asks you to show
 * up, a score you are behind on makes you want to.
 *
 * Rematch creates a private room and lands you in its lobby, where the code and
 * the share button already live. It cannot notify the other person, because
 * there is no push infrastructure in the app at all — so the copy promises a
 * table, not a summons.
 */
@Composable
internal fun RivalryCard(
    rivalry: Rivalry,
    onRematch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val headline = when (rivalry.standing) {
        Rivalry.Standing.Ahead -> stringResource(
            Res.string.home_rivalry_ahead,
            rivalry.handsWon,
            rivalry.opponentHandsWon,
            rivalry.displayName,
        )
        Rivalry.Standing.Behind -> stringResource(
            Res.string.home_rivalry_behind,
            rivalry.displayName,
            rivalry.opponentHandsWon,
            rivalry.handsWon,
        )
        Rivalry.Standing.Even -> stringResource(
            Res.string.home_rivalry_even,
            rivalry.displayName,
            rivalry.handsWon,
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radii.Card.shape)
            .background(AppTheme.colors.surface.color)
            .border(1.dp, AppTheme.colors.border.color, Radii.Card.shape)
            .padding(Dimension.D600),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarCircle(
                name = rivalry.displayName,
                emoji = rivalry.avatarEmoji,
                backgroundColorHex = rivalry.avatarBackgroundColorHex,
                size = 40.dp,
            )
            HorizontalSpacerD400()
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = headline,
                    typography = AppTheme.typography.Heading.H700,
                    color = AppTheme.colors.content,
                )
                VerticalSpacerD200()
                Text(
                    text = rivalry.detailLine(),
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.contentSecondary,
                )
            }
        }
        VerticalSpacerD500()
        ButtonPrimary(
            onClick = onRematch,
            size = ButtonSize.Small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(Res.string.home_rivalry_rematch_button))
        }
    }
}

/**
 * The biggest pot is the better second line when there is one to brag about; a
 * player who has never taken a pot off this opponent gets the session count
 * instead, which is at least true.
 */
@Composable
private fun Rivalry.detailLine(): String =
    if (biggestPotWon > 0) {
        stringResource(
            Res.string.home_rivalry_biggest_pot,
            sessionsTogether,
            formatCompactChips(biggestPotWon),
        )
    } else {
        stringResource(Res.string.home_rivalry_sessions, sessionsTogether)
    }

private fun previewRivalry(
    handsWon: Int = 11,
    opponentHandsWon: Int = 14,
    biggestPotWon: Long = 4_200,
) = Rivalry(
    opponentId = "opp-1",
    displayName = "Mark",
    avatarEmoji = "🦅",
    avatarBackgroundColorHex = "#5B8C7A",
    handsTogether = handsWon + opponentHandsWon + 6,
    handsWon = handsWon,
    opponentHandsWon = opponentHandsWon,
    biggestPotWon = biggestPotWon,
    sessionsTogether = 4,
)

@Preview
@Composable
private fun RivalryCardPreview_Behind() {
    PreviewContent {
        Box(modifier = Modifier.padding(16.dp)) {
            RivalryCard(rivalry = previewRivalry(), onRematch = {})
        }
    }
}

@Preview
@Composable
private fun RivalryCardPreview_Ahead() {
    PreviewContent {
        Box(modifier = Modifier.padding(16.dp)) {
            RivalryCard(
                rivalry = previewRivalry(handsWon = 18, opponentHandsWon = 9),
                onRematch = {},
            )
        }
    }
}

@Preview
@Composable
private fun RivalryCardPreview_Even() {
    PreviewContent {
        Box(modifier = Modifier.padding(16.dp)) {
            RivalryCard(
                rivalry = previewRivalry(handsWon = 12, opponentHandsWon = 12),
                onRematch = {},
            )
        }
    }
}

@Preview
@Composable
private fun RivalryCardPreview_NeverWonAPot() {
    PreviewContent {
        Box(modifier = Modifier.padding(16.dp)) {
            RivalryCard(
                rivalry = previewRivalry(handsWon = 0, opponentHandsWon = 9, biggestPotWon = 0),
                onRematch = {},
            )
        }
    }
}

@Preview
@Composable
private fun RivalryCardPreview_LongName() {
    PreviewContent {
        Box(modifier = Modifier.padding(16.dp)) {
            RivalryCard(
                rivalry = previewRivalry().copy(displayName = "ThunderingArmadillo42"),
                onRematch = {},
            )
        }
    }
}
