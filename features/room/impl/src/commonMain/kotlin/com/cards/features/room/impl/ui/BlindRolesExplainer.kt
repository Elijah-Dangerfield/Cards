package com.dangerfield.cards.features.room.impl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.room_blind_roles_big_blind_description
import cards.libraries.resources.generated.resources.room_blind_roles_big_blind_title
import cards.libraries.resources.generated.resources.room_blind_roles_dealer_description
import cards.libraries.resources.generated.resources.room_blind_roles_dealer_title
import cards.libraries.resources.generated.resources.room_blind_roles_small_blind_description
import cards.libraries.resources.generated.resources.room_blind_roles_small_blind_title
import cards.libraries.resources.generated.resources.room_blind_roles_title
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.poker.BlindMarker
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.VerticalSpacerD50
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview

/**
 * Quick-reference modal explaining what the D / SB / BB chips on each player
 * mean. Triggered by tapping any of those markers — the hit area is
 * intentionally generous so the small visible chip is forgiving to find.
 */
@Composable
internal fun BlindRolesExplainer(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        topAccessory = topAccessoryEmoji(emoji = "🎲"),
        title = {
            Text(
                text = stringResource(Res.string.room_blind_roles_title),
                typography = AppTheme.typography.Heading.H800,
                color = AppTheme.colors.content,
                textAlign = TextAlign.Center,
            )
        },
        itemSpacing = Dimension.D800,
    ) {
        // Show the exact markers the felt renders (white "cutout" chips), so the
        // legend can't drift from the table — we no longer use colored chips.
        RoleRow(
            isDealer = true,
            title = stringResource(Res.string.room_blind_roles_dealer_title),
            description = stringResource(Res.string.room_blind_roles_dealer_description),
        )
        RoleRow(
            isSmallBlind = true,
            title = stringResource(Res.string.room_blind_roles_small_blind_title),
            description = stringResource(Res.string.room_blind_roles_small_blind_description),
        )
        RoleRow(
            isBigBlind = true,
            title = stringResource(Res.string.room_blind_roles_big_blind_title),
            description = stringResource(Res.string.room_blind_roles_big_blind_description),
        )
    }
}

@Composable
private fun RoleRow(
    title: String,
    description: String,
    isDealer: Boolean = false,
    isSmallBlind: Boolean = false,
    isBigBlind: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.size(34.dp),
            contentAlignment = Alignment.Center,
        ) {
            BlindMarker(
                isDealer = isDealer,
                isSmallBlind = isSmallBlind,
                isBigBlind = isBigBlind,
                cutoutColor = AppTheme.colors.surface.color,
            )
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.content,
            )
            VerticalSpacerD50()
            Text(
                text = description,
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
            )
        }
    }
}

@Preview
@Composable
private fun BlindRolesExplainerPreview() {
    PreviewContent {
        BlindRolesExplainer(onDismiss = {})
    }
}
