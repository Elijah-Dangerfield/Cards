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
 * The whole system is too tall for one preview, so it's split into logical groups you can open
 * side by side in the IDE preview pane:
 *
 *   • [ColorPreview]      — the palette: surfaces, content, accents | status, borders, gradient, categorical.
 *   • [TypographyPreview] — the type scale: Display + Heading | Body + Label + Caption.
 *   • [ComponentsPreview] — built from the tokens: Buttons · Banner · Forms · Radii (+ Overlays pointer).
 *
 * Each is a wide, two-column page so it spreads horizontally instead of running tall. For a single
 * area, every catalog file (ColorCatalog, …) and component file (Button.kt, …) has its own focused
 * @Preview. Add a token/component to the matching *CatalogBody() and it shows up in the right group.
 */

@Preview(widthDp = 2400, heightDp = 7000)
@Composable
private fun ColorPreview() {
    CatalogScaffold(
        title = "Color",
        subtitle = "Warm felt, dark-only. Tokens name a role, never a literal color. Reach for a role — never a raw ColorResource.",
    ) {
        TwoColumn(
            left = { ColorCatalogBodyPrimary() },
            right = { ColorCatalogBodySupport() },
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

@Preview(widthDp = 2400, heightDp = 5000)
@Composable
private fun ComponentsPreview() {
    CatalogScaffold(
        title = "Components",
        subtitle = "Built from the tokens — opinionated and congruent by default.",
    ) {
        TwoColumn(
            left = {
                Area("Buttons", "Emphasis × treatment × state, the accent recolor, and the size ramp.") {
                    ButtonCatalogBody()
                }
                Area("Banner", "Inline non-modal messages in every tone.") {
                    BannerCatalogBody()
                }
            },
            right = {
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
            },
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
            verticalArrangement = Arrangement.spacedBy(Dimension.D1100),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimension.D300)) {
                Text(text = title, typography = AppTheme.typography.Display.D1500)
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
            verticalArrangement = Arrangement.spacedBy(Dimension.D1100),
            content = left,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Dimension.D1100),
            content = right,
        )
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
