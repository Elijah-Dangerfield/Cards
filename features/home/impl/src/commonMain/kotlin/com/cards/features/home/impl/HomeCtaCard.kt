package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii

/**
 * Home's primary CTA tile per product-spec §2.4 — one tile per play
 * route (Practice, Quick Match, Friend Game, Tournament). Sized for
 * "big-surface" Duolingo energy: chunky glyph badge, type-driven
 * title + supporting line, optional trailing label for "Soon"-style
 * states, all on a softly graded muted-accent background.
 *
 * Diverges from the existing `FeatureCard` primitive intentionally
 * — Home's CTAs are taller, get a trailing badge slot (V2 / soft-
 * gated states), and skip the chevron in favor of the trailing slot.
 * If a second screen ever wants the same shape, lift to
 * `:libraries:ui`. Until then this lives next to its only caller so
 * the brand-load-bearing surface can iterate without churning the
 * DS.
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .clip(Radii.R900.shape)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        accent.copy(alpha = if (enabled) 0.92f else 0.4f),
                        accent.copy(alpha = if (enabled) 0.66f else 0.28f),
                    ),
                ),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Dimension.D700, vertical = Dimension.D800),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimension.D700),
    ) {
        GlyphBadge(glyph = glyph)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                typography = AppTheme.typography.Heading.H600,
                color = AppTheme.colors.text,
            )
            Text(
                text = subtitle,
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.text,
                modifier = Modifier.alpha(0.78f),
            )
        }
        if (trailing != null) TrailingChip(label = trailing)
    }
}

@Composable
private fun GlyphBadge(glyph: String) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(Radii.R700.shape)
            .background(Color.White.copy(alpha = 0.12f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.08f),
                shape = Radii.R700.shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            typography = AppTheme.typography.Heading.H800,
            color = AppTheme.colors.text,
        )
    }
}

@Composable
private fun TrailingChip(label: String) {
    Box(
        modifier = Modifier
            .clip(Radii.R500.shape)
            .background(Color.Black.copy(alpha = 0.25f))
            .padding(horizontal = Dimension.D400, vertical = Dimension.D200),
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Label.L400,
            color = AppTheme.colors.text,
        )
    }
}
