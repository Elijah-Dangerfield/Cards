package com.dangerfield.cards.features.room.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.room_blind_roles_big_blind_description
import cards.libraries.resources.generated.resources.room_blind_roles_big_blind_label
import cards.libraries.resources.generated.resources.room_blind_roles_big_blind_title
import cards.libraries.resources.generated.resources.room_blind_roles_dealer_description
import cards.libraries.resources.generated.resources.room_blind_roles_dealer_label
import cards.libraries.resources.generated.resources.room_blind_roles_dealer_title
import cards.libraries.resources.generated.resources.room_blind_roles_small_blind_description
import cards.libraries.resources.generated.resources.room_blind_roles_small_blind_label
import cards.libraries.resources.generated.resources.room_blind_roles_small_blind_title
import cards.libraries.resources.generated.resources.room_blind_roles_title
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.VerticalSpacerD50
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

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
                color = AppTheme.colors.onSurfacePrimary,
                textAlign = TextAlign.Center,
            )
        },
        itemSpacing = Dimension.D800,
    ) {
        RoleRow(
            label = stringResource(Res.string.room_blind_roles_dealer_label),
            bg = PokerPalette.DealerWhite,
            title = stringResource(Res.string.room_blind_roles_dealer_title),
            description = stringResource(Res.string.room_blind_roles_dealer_description),
        )
        RoleRow(
            label = stringResource(Res.string.room_blind_roles_small_blind_label),
            bg = PokerPalette.ChipGold,
            title = stringResource(Res.string.room_blind_roles_small_blind_title),
            description = stringResource(Res.string.room_blind_roles_small_blind_description),
        )
        RoleRow(
            label = stringResource(Res.string.room_blind_roles_big_blind_label),
            bg = PokerPalette.BlindRed,
            title = stringResource(Res.string.room_blind_roles_big_blind_title),
            description = stringResource(Res.string.room_blind_roles_big_blind_description),
        )
    }
}

@Composable
private fun RoleRow(label: String, bg: Color, title: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                typography = AppTheme.typography.Body.B500.Bold,
                color = AppTheme.colors.background,
            )
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.onSurfacePrimary,
            )
            VerticalSpacerD50()
            Text(
                text = description,
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.onSurfaceSecondary,
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
