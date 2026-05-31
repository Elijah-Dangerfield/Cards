package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.home_tutorial_banner_dismiss_a11y
import cards.libraries.resources.generated.resources.home_tutorial_banner_start_button
import cards.libraries.resources.generated.resources.home_tutorial_banner_subtitle
import cards.libraries.resources.generated.resources.home_tutorial_banner_title
import com.dangerfield.cards.libraries.ui.Elevation
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.bounceClick
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.icon.IconButton
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.elevation
import com.dangerfield.cards.libraries.ui.system.color.FeatureCardAccents
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.HorizontalSpacerD200
import com.dangerfield.cards.system.HorizontalSpacerD500
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD100
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Compact one-row banner: gold icon tile, two-line title/subtitle, small
 * Start pill, subdued dismiss. Lives above the Home header until the user
 * dismisses it; the tutorial itself remains reachable from Settings →
 * "How to play".
 */
@Composable
internal fun TutorialBanner(
    onStart: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radii.Card.shape)
            .border(1.dp, AppTheme.colors.border.color, Radii.Card.shape)
            .background(AppTheme.colors.surface.color)
            .bounceClick(onClick = onStart)
            .padding(horizontal = Dimension.D500, vertical = Dimension.D500),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile()
        HorizontalSpacerD500()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.home_tutorial_banner_title),
                typography = AppTheme.typography.Body.B700.SemiBold,
                color = AppTheme.colors.content,
            )
            VerticalSpacerD100()
            Text(
                text = stringResource(Res.string.home_tutorial_banner_subtitle),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
            )
        }
        HorizontalSpacerD500()
        ButtonSecondary(
            onClick = onStart,
            size = ButtonSize.Small,
        ) {
            Text(stringResource(Res.string.home_tutorial_banner_start_button))
        }
        HorizontalSpacerD200()
        IconButton(
            icon = Icons.X(stringResource(Res.string.home_tutorial_banner_dismiss_a11y)),
            onClick = onDismiss,
            size = IconButton.Size.Small,
            backgroundColor = null,
            iconColor = AppTheme.colors.contentSecondary,
        )
    }
}

@Composable
private fun IconTile() {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(Radii.R400.shape)
            .background(FeatureCardAccents.Gold),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "🔑",
            typography = AppTheme.typography.Heading.H500,
            color = AppTheme.colors.content,
        )
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
