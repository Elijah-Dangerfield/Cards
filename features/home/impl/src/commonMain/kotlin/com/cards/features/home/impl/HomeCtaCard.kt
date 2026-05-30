package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.system.color.FeatureCardAccents
import com.dangerfield.cards.libraries.ui.components.HorizontalDivider
import com.dangerfield.cards.libraries.ui.components.icon.Icon
import com.dangerfield.cards.libraries.ui.components.icon.IconSize
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD500
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Home's primary CTA tile per product-spec §2.4 — one tile per play
 * route (Practice, Quick Match, Friend Game). Dark, compact, list-row
 * shaped: small accent-coloured glyph block on the left, title +
 * supporting line stacked, trailing chevron (or a "Soon"-style chip
 * for soft-gated states).
 *
 * Earlier iteration used a full-card gradient that ate visual weight
 * Home now spends on Friends / Recent achievements. The list-row shape
 * keeps the tile recognizable while letting the surrounding shelves
 * breathe.
 *
 * Lives next to its only caller for now; lift to `:libraries:ui` if
 * a second screen wants the same shape.
 */
@Composable
internal fun HomeCtaCard(
    title: String,
    subtitle: String,
    glyph: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: String? = null,
) {
    Column {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.55f)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = Dimension.D500, vertical = Dimension.D500),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimension.D500),
        ) {
            GlyphBadge(glyph = glyph, accent = accent)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    typography = AppTheme.typography.Heading.H700,
                    color = AppTheme.colors.text,
                )
                Text(
                    text = subtitle,
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.textSecondary,
                )
            }
            if (trailing != null) {
                TrailingChip(label = trailing)
            } else {
                Icon(
                    icon = Icons.ChevronRight("Open"),
                    color = AppTheme.colors.textSecondary,
                    size = IconSize.Small,
                )
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun GlyphBadge(glyph: String, accent: Color) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(Radii.R500.shape)
            .background(accent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            typography = AppTheme.typography.Heading.H1000,
            color = AppTheme.colors.text,
        )
    }
}

@Composable
private fun TrailingChip(label: String) {
    Box(
        modifier = Modifier
            .clip(Radii.R500.shape)
            .background(AppTheme.colors.surfaceSecondary.color)
            .padding(horizontal = Dimension.D400, vertical = Dimension.D200),
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Label.L400,
            color = AppTheme.colors.textSecondary,
        )
    }
}

// ---------------------------------------------------------------------------
// Previews — one card per accent, all three states the production CTAs
// render in: plain (chevron), soft-gated (trailing "Soon" chip), and
// disabled (full-card alpha). Stacked vertically to exercise spacing.
// ---------------------------------------------------------------------------

@Preview
@Composable
private fun HomeCtaCardPreview_Stack() {
    PreviewContent(contentPadding = PaddingValues(16.dp)) {
        Column {
            HomeCtaCard(
                title = "Practice",
                subtitle = "Solo vs. bots",
                glyph = "🤖",
                accent = FeatureCardAccents.Green,
                onClick = {},
            )
            VerticalSpacerD500()
            HomeCtaCard(
                title = "Quick Match",
                subtitle = "Public seat · one tap",
                glyph = "⏳",
                accent = FeatureCardAccents.Blue,
                onClick = {},
                trailing = "Soon",
            )
            VerticalSpacerD500()
            HomeCtaCard(
                title = "Friend Game",
                subtitle = "Room code · just you and yours",
                glyph = "👭",
                accent = FeatureCardAccents.Gold,
                onClick = {},
            )
            VerticalSpacerD500()
            HomeCtaCard(
                title = "Tournament",
                subtitle = "Royal Flush Tournament. Quarterly.",
                glyph = "♛",
                accent = FeatureCardAccents.Magenta,
                onClick = {},
                enabled = false,
                trailing = "V2",
            )
        }
    }
}

