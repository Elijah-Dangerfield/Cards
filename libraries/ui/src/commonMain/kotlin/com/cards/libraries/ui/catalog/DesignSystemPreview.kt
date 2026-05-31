package com.dangerfield.cards.libraries.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import org.jetbrains.compose.ui.tooling.preview.Preview

/*
 * # Design-system catalog — grouped previews
 *
 * The system is too big for one preview (a single combined page exceeds the IDE's max render size),
 * so it's split into small, focused group previews you can open in the IDE preview pane. Each is a
 * wide, two-column page so it spreads horizontally instead of running tall:
 *
 *   • [ColorPrimaryPreview] — surfaces + content | accents.
 *   • [ColorSupportPreview] — status + borders + gradient | categorical (poker / rarity / league).
 *   • [TypographyPreview]   — Display + Heading | Body + Label + Caption.
 *
 * Components don't have a combined preview on purpose — each one (Button, Switch, Banner, …) has its
 * own catalog-style @Preview in its component file, and Radii in RadiiCatalog.kt. Keeping them
 * separate is what keeps every preview under the render cap. Add a token/component to the matching
 * *CatalogBody() / section composable and it shows up in the right place.
 */

@Preview(widthDp = 2400, heightDp = 4500)
@Composable
private fun ColorPrimaryPreview() {
    CatalogScaffold(
        title = "Color · core",
        subtitle = "The everyday tokens: surfaces, the content ramp, and the accent triads. Reach for a role, never a raw color.",
    ) {
        TwoColumn(
            left = { ColorSurfacesContent() },
            right = { ColorAccents() },
        )
    }
}

@Preview(widthDp = 2400, heightDp = 4500)
@Composable
private fun ColorSupportPreview() {
    CatalogScaffold(
        title = "Color · status & categorical",
        subtitle = "Status states, borders, the one gradient, and the categorical game / rarity / league groups.",
    ) {
        TwoColumn(
            left = { ColorStatusBordersGradient() },
            right = { ColorCategorical() },
        )
    }
}

@Preview(widthDp = 2400, heightDp = 4000)
@Composable
private fun TypographyPreview() {
    CatalogScaffold(
        title = "Typography",
        subtitle = "Display = serif (the felt headline; its italic is the dialog/sheet title). Heading / Body / Label / Caption = sans.",
    ) {
        TwoColumn(
            left = { TypographyCatalogBodyHeadlines() },
            right = { TypographyCatalogBodyText() },
        )
    }
}

/** Themed, scrollable page with a cover header — the shell every group preview shares. */
@Composable
private fun CatalogScaffold(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    PreviewContent {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Dimension.D1000),
            verticalArrangement = Arrangement.spacedBy(Dimension.D900),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimension.D300)) {
                Text(text = title, typography = AppTheme.typography.Display.D1300)
                Text(
                    text = subtitle,
                    typography = AppTheme.typography.Body.B600,
                    color = AppTheme.colors.contentSecondary,
                )
            }
            content()
        }
    }
}

/** Two balanced columns so a page spreads horizontally instead of running tall. */
@Composable
private fun TwoColumn(
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimension.D1100),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Dimension.D900),
            content = left,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Dimension.D900),
            content = right,
        )
    }
}
