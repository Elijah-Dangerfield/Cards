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
 * # DesignSystemPreview — the whole system on one page.
 *
 * Open this in the IDE preview pane to scroll the entire design system top to bottom: a cover, then
 * every area (Color · Typography · Radii · Buttons · Banner · Forms) rendered live next to its
 * "when to use it" notes. Each area also has its own focused @Preview in its catalog file
 * (ColorCatalog, ButtonCatalog, …) when you want just that one.
 *
 * This is documentation, not a screen — read it like a spec sheet. If you add a component or token,
 * add it to the matching *CatalogBody() and it shows up here automatically.
 */
// Wide + two columns: spreads horizontally so the page is ~half as tall as a single stack. heightDp
// is intentionally generous (trailing blank space is harmless; too small clips) — trim to taste.
@Preview(widthDp = 2400, heightDp = 15000)
@Composable
private fun DesignSystemPreview() {
    PreviewContent {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Dimension.D1000),
            verticalArrangement = Arrangement.spacedBy(Dimension.D1100),
        ) {
            // Cover spans the full width above the columns.
            Column(verticalArrangement = Arrangement.spacedBy(Dimension.D300)) {
                Text(text = "Cards Design System", typography = AppTheme.typography.Display.D1500)
                Text(
                    text = "Warm felt · dark-only. Role-named tokens, an opinionated component set, and one " +
                        "place to review it all. Below: color, type, radii, buttons, banner, forms — each " +
                        "shown live with notes on when to reach for it.",
                    typography = AppTheme.typography.Body.B600,
                    color = AppTheme.colors.contentSecondary,
                )
            }

            // Two balanced columns so the catalog spreads horizontally instead of running tall.
            // Areas are split by rough height: the big Color ladder + a couple of mediums on the
            // left, the rest on the right.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimension.D1100),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Dimension.D1100),
                ) {
                    Area("Color", "Role-named tokens — surfaces, the content ramp, accents, status, borders, categorical.") {
                        ColorCatalogBody()
                    }
                    Area("Buttons", "Emphasis × treatment × state, the accent recolor, and the size ramp.") {
                        ButtonCatalogBody()
                    }
                    Area("Banner", "Inline non-modal messages in every tone.") {
                        BannerCatalogBody()
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Dimension.D1100),
                ) {
                    Area("Typography", "The serif Display headline + the sans Heading / Body / Label / Caption ramps.") {
                        TypographyCatalogBody()
                    }
                    Area("Forms", "Text field, switch, checkbox, radio — every state.") {
                        FormCatalogBody()
                    }
                    Area("Radii", "Semantic corner radii and the raw scale behind them.") {
                        RadiiCatalogBody()
                    }
                    // Overlays don't render inline (they host as full-screen scrims) — point to their previews.
                    Area("Overlays", "Dialog & BottomSheet host as full-screen scrims, so they can't render inline here.") {
                        Text(
                            text = "See the focused previews in Dialog.kt and BottomSheet.kt. Both own the typography " +
                                "of their title/body slots: a bare Text in a title slot becomes the serif felt headline, " +
                                "body text becomes Body.B500 in contentSecondary.",
                            typography = AppTheme.typography.Body.B500,
                            color = AppTheme.colors.contentSecondary,
                        )
                    }
                }
            }
        }
    }
}

/** One titled area in a column: a divider, the area header, then its body. */
@Composable
private fun ColumnScope.Area(title: String, intro: String, body: @Composable () -> Unit) {
    CatalogDivider()
    Column(verticalArrangement = Arrangement.spacedBy(Dimension.D900)) {
        CatalogHeader(title, intro)
        body()
    }
}
