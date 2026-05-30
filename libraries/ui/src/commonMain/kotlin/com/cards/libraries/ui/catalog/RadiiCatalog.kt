package com.dangerfield.cards.libraries.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.Radius
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Corner radii — the semantic getters components actually use, and the raw R-scale beneath them.
 * Buttons are a springy 16dp rect (was a full pill), cards 18dp, banners 14dp.
 */
@Preview(widthDp = 1100, heightDp = 1000)
@Composable
private fun RadiiCatalog() {
    CatalogPage(
        title = "Radii",
        subtitle = "Reach for the semantic getter (Button / Card / Banner / Callout) — the R-scale is the raw ramp behind them.",
    ) {
        CatalogSection("Semantic") {
            SwatchFlow {
                RadiusTile("Button", Radii.Button)
                RadiusTile("Card", Radii.Card)
                RadiusTile("Banner", Radii.Banner)
                RadiusTile("Callout", Radii.Callout)
                RadiusTile("IconButton", Radii.IconButton)
                RadiusTile("Round", Radii.Round)
                RadiusTile("None", Radii.None)
            }
        }
        CatalogSection("Scale") {
            SwatchFlow {
                RadiusTile("R300", Radii.R300)
                RadiusTile("R400", Radii.R400)
                RadiusTile("R500", Radii.R500)
                RadiusTile("R600", Radii.R600)
                RadiusTile("R700", Radii.R700)
                RadiusTile("R750", Radii.R750)
                RadiusTile("R800", Radii.R800)
                RadiusTile("R850", Radii.R850)
                RadiusTile("R900", Radii.R900)
                RadiusTile("R1000", Radii.R1000)
            }
        }
    }
}

@Composable
private fun RadiusTile(name: String, radius: Radius) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimension.D300)) {
        Box(
            modifier = Modifier
                .size(110.dp)
                .background(AppTheme.colors.surfaceHigh.color, shape = radius.shape)
                .border(1.dp, AppTheme.colors.borderStrong.color, radius.shape),
        )
        Text(text = name, typography = AppTheme.typography.Label.L500)
    }
}
