package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD200

/**
 * Featured-cosmetic banner per product-spec §2.4 — the limited-time
 * / current-drop shelf at the bottom of Home. Tap routes into the
 * shop product details.
 *
 * The swatch on the left is a tiny render of the actual cosmetic
 * (felt color, card-back style) rather than a stock product image —
 * intentional: forces the same DS primitives that paint the table
 * to paint the preview, which keeps the marketing surface honest
 * about what the user is actually buying. Pairs with the
 * "previews on cosmetics" todo (see docs/todo.md).
 *
 * V1 ships canned data — once the shop's "featured drop" endpoint
 * exists, [item] becomes a real reactive value off the catalog.
 */
@Composable
internal fun FeaturedCosmeticCard(
    item: FeaturedCosmetic,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "THIS WEEK",
            typography = AppTheme.typography.Label.L400,
            color = AppTheme.colors.textSecondary,
        )
        VerticalSpacerD200()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(Radii.R900.shape)
                .background(AppTheme.colors.surfaceSecondary.color)
                .border(
                    width = 1.dp,
                    color = AppTheme.colors.border.color,
                    shape = Radii.R900.shape,
                )
                .clickable(onClick = onClick)
                .padding(Dimension.D600),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimension.D600),
        ) {
            CosmeticSwatch(swatch = item.swatchColor)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    typography = AppTheme.typography.Heading.H600,
                    color = AppTheme.colors.text,
                )
                Text(
                    text = item.tagline,
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.textSecondary,
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = AppTheme.colors.textSecondary.color,
            )
        }
    }
}

@Composable
private fun CosmeticSwatch(swatch: Color) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(Radii.R700.shape)
            .background(swatch)
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.08f),
                shape = Radii.R700.shape,
            ),
    )
}

@Immutable
internal data class FeaturedCosmetic(
    val name: String,
    val tagline: String,
    val swatchColor: Color,
)
