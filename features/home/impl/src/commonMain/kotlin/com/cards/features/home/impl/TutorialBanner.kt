package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.background
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
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.bounceClick
import com.dangerfield.cards.libraries.ui.components.icon.IconButton
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.FeatureCardAccents
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.HorizontalSpacerD500
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD100
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * The "New here?" onboarding banner: a green gradient card with a mortarboard
 * tile, two-line pitch, and a white "Start" pill — same gradient family as
 * Home's Play cards. Lives above the Home header until dismissed (the subtle
 * corner ✕); the tutorial stays reachable from Settings → "How to play".
 */
@Composable
internal fun TutorialBanner(
    onStart: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(Radii.R900.shape)
                .background(playGradient(FeatureCardAccents.Green))
                .bounceClick(onClick = onStart)
                .padding(horizontal = Dimension.D600, vertical = Dimension.D600),
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
                    color = AppTheme.colors.content.withAlpha(0.78f),
                )
            }
            HorizontalSpacerD500()
            StartPill(onClick = onStart)
        }
        // Low-emphasis dismiss tucked into the corner so the card reads clean.
        IconButton(
            icon = Icons.X(stringResource(Res.string.home_tutorial_banner_dismiss_a11y)),
            onClick = onDismiss,
            size = IconButton.Size.Small,
            backgroundColor = null,
            iconColor = AppTheme.colors.content.withAlpha(0.6f),
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

/** White "Start" pill — composed from the inverse [content]-on-[background]
 *  tokens since the DS has no light filled button. */
@Composable
private fun StartPill(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(Radii.Round.shape)
            .background(AppTheme.colors.content.color)
            .bounceClick(onClick = onClick)
            .padding(horizontal = Dimension.D600, vertical = Dimension.D300),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(Res.string.home_tutorial_banner_start_button),
            typography = AppTheme.typography.Body.B600,
            color = AppTheme.colors.background,
        )
    }
}

@Composable
private fun IconTile() {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(Radii.R400.shape)
            .background(AppTheme.colors.background.color.copy(alpha = 0.28f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "🎓",
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
