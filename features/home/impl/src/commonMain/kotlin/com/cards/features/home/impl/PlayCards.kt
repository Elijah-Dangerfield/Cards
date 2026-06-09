package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.StatusPill
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.FeatureCardAccents
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Home's "Play" picker, per the redesign: one large [PlayFeatureCard] hero
 * over a row of two half-width [PlayTileCard]s. Each card is an accent-tinted
 * gradient panel with a card-suit glyph tile; tapping opens the matching
 * bottom sheet (or routes onward). Accent comes from [FeatureCardAccents] so
 * the play options keep a stable colour identity across the app.
 *
 * Home-local (like the old HomeCtaCard) — lift to :libraries:ui if a second
 * screen ever wants the same shape.
 */

/** Vertical accent wash — brighter at the top, settling into the dark bg.
 *  Shared with [TutorialBanner] so the Play cards + the "New here?" banner
 *  read as the same gradient family. */
internal fun playGradient(accent: Color): Brush = Brush.verticalGradient(
    colors = listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.68f)),
)

@Composable
private fun GlyphTile(glyph: String, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(Radii.R600.shape)
            .background(AppTheme.colors.content.color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.content,
        )
    }
}

/**
 * The hero play option (Practice): glyph tile, title + subtitle, and a
 * trailing [cta] button. The whole card is tappable; the button is the
 * matching affordance.
 */
@Composable
internal fun PlayFeatureCard(
    title: String,
    subtitle: String,
    glyph: String,
    accent: Color,
    cta: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radii.R900.shape)
            .background(playGradient(accent))
            .clickable(onClick = onClick)
            .padding(horizontal = Dimension.D700, vertical = Dimension.D900),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimension.D500),
    ) {
        GlyphTile(glyph = glyph, size = 52.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                typography = AppTheme.typography.Heading.H600,
                color = AppTheme.colors.content,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.content.withAlpha(0.78f),
            )
        }
        ButtonSecondary(onClick = onClick, size = ButtonSize.Small) {
            Text(cta)
        }
    }
}

/**
 * A half-width play option (Quick Match, Friend Game): glyph tile at the top,
 * title + short subtitle anchored to the bottom. Pass `Modifier.weight(1f)`
 * from the caller so two sit side by side. An optional [tag] renders a small
 * status pill in the top-right corner — used to mark a not-yet-shipped option
 * as "Coming soon".
 */
@Composable
internal fun PlayTileCard(
    title: String,
    subtitle: String,
    glyph: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
) {
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(Radii.R900.shape)
                .background(playGradient(accent))
                .clickable(onClick = onClick)
                .padding(Dimension.D600),
        ) {
            GlyphTile(glyph = glyph, size = 40.dp)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = title,
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.content,
            )
            Text(
                text = subtitle,
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.content.withAlpha(0.78f),
            )
        }
        tag?.let {
            StatusPill(
                text = it,
                background = AppTheme.colors.surfaceHigh,
                foreground = AppTheme.colors.content,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(Dimension.D400),
            )
        }
    }
}

@Preview
@Composable
private fun PlayCardsPreview() {
    PreviewContent(contentPadding = PaddingValues(16.dp)) {
        Column {
            PlayFeatureCard(
                title = "Practice",
                subtitle = "Sharpen up against bots",
                glyph = "♠",
                accent = FeatureCardAccents.Green,
                cta = "Play",
                onClick = {},
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PlayTileCard(
                    title = "Quick Match",
                    subtitle = "Find a table",
                    glyph = "✦",
                    accent = FeatureCardAccents.Blue,
                    onClick = {},
                    modifier = Modifier.weight(1f),
                    tag = "Coming soon",
                )
                PlayTileCard(
                    title = "Friend Game",
                    subtitle = "Code or join",
                    glyph = "♣",
                    accent = FeatureCardAccents.Gold,
                    onClick = {},
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
