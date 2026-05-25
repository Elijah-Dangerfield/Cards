package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.icon.IconButton
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.HorizontalSpacerD400
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD200
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Banner shown above the Home header for users who haven't dismissed it
 * yet. Tapping Start launches the scripted poker tutorial; the X dismisses
 * the banner permanently (the tutorial stays accessible from Settings →
 * "How to play"). Visually echoes [ActiveRoomBanner] so the home surface
 * has a consistent card chrome.
 */
@Composable
internal fun TutorialBanner(
    onStart: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radii.Card.shape)
            .background(AppTheme.colors.background.color)
            .border(2.dp, AppTheme.colors.borderSecondary.color, Radii.Card.shape)
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
                    text = "🎓",
                    typography = AppTheme.typography.Heading.H600,
                    color = AppTheme.colors.text,
                )
            }
            HorizontalSpacerD400()
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "New to poker?",
                    typography = AppTheme.typography.Heading.H500,
                    color = AppTheme.colors.text,
                )
                VerticalSpacerD200()
                Text(
                    text = "2-minute tutorial — we'll walk you through a few hands.",
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.textSecondary,
                )
            }
            HorizontalSpacerD400()
            IconButton(
                icon = Icons.X("Dismiss tutorial banner"),
                onClick = onDismiss,
                size = IconButton.Size.Small,
            )
        }
        VerticalSpacerD200()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ButtonPrimary(
                onClick = onStart,
                size = ButtonSize.Medium,
            ) {
                Text(text = "Start")
            }
        }
    }
}

@Preview
@Composable
private fun TutorialBannerPreview() {
    PreviewContent {
        Box(
            modifier = Modifier
                .background(AppTheme.colors.background.color)
                .padding(Dimension.D600),
        ) {
            TutorialBanner(onStart = {}, onDismiss = {})
        }
    }
}
