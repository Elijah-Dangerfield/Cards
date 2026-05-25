package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.ButtonGhost
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonTertiary
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.HorizontalSpacerD400
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD500
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
internal fun ActiveRoomBanner(
    code: String,
    onRejoin: () -> Unit,
    onForfeit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingForfeit by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radii.Card.shape)
            .background(AppTheme.colors.background.color)
            .border(2.dp, AppTheme.colors.border.color, Radii.Card.shape)
            .padding(Dimension.D600),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(Radii.R600.shape)
                    .background(AppTheme.colors.surfacePrimary.color),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "♠",
                    typography = AppTheme.typography.Heading.H600,
                    color = AppTheme.colors.text,
                )
            }
            HorizontalSpacerD400()
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "You have an ongoing game",
                    typography = AppTheme.typography.Heading.H500,
                    color = AppTheme.colors.text,
                )
                VerticalSpacerD200()
                Text(
                    text = "Room $code is still holding your seat.",
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.textSecondary,
                )
            }
        }
        VerticalSpacerD500()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimension.D400),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                ButtonSecondary(
                    onClick = { confirmingForfeit = true },
                    size = ButtonSize.Medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "Forfeit")
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                ButtonPrimary(
                    onClick = onRejoin,
                    size = ButtonSize.Medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "Rejoin")
                }
            }
        }
    }

    if (confirmingForfeit) {
        Dialog(
            title = "Leave room $code?",
            description = "Your seat will be released and any stack at the table goes back to your wallet.",
            primaryButtonText = "Forfeit",
            secondaryButtonText = "Stay",
            onDismissRequest = { confirmingForfeit = false },
            onPrimaryButtonClicked = {
                confirmingForfeit = false
                onForfeit()
            },
            onSecondaryButtonClicked = { confirmingForfeit = false },
        )
    }
}

@Preview
@Composable
private fun ActiveRoomBannerPreview() {
    PreviewContent {
        Box(
            modifier = Modifier
                .background(AppTheme.colors.background.color)
                .padding(Dimension.D600),
        ) {
            ActiveRoomBanner(
                code = "ABC123",
                onRejoin = {},
                onForfeit = {},
            )
        }
    }
}
