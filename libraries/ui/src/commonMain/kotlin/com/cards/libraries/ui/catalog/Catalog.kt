package com.dangerfield.cards.libraries.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.ui.system.color.toHexString
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii

/*
 * # Design-system catalog
 *
 * A browsable, documentation-style tour of the system — not a screen, a *reference*. Open
 * [DesignSystemPreview] for the whole thing top-to-bottom, or any single page preview here for one
 * area. Every token / component is shown live next to a short "when to use it" note, so the catalog
 * doubles as the spec.
 *
 * These are intentionally wide (widthDp/heightDp set well past a phone) — read them like a page,
 * not a device. Keep catalog content here, not scattered across component files, so this stays the
 * single source of truth for reviewing the system.
 */

/** Page heading — a big title + one-line intro. Reused by every page and the combined preview. */
@Composable
internal fun CatalogHeader(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimension.D200)) {
        Text(text = title, typography = AppTheme.typography.Display.D1100)
        if (subtitle != null) {
            Text(
                text = subtitle,
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
            )
        }
    }
}

/** Root for a single-area catalog page: themed background, header, scrollable column of sections. */
@Composable
internal fun CatalogPage(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    PreviewContent {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Dimension.D1000),
            verticalArrangement = Arrangement.spacedBy(Dimension.D900),
        ) {
            CatalogHeader(title, subtitle)
            content()
        }
    }
}

/** A titled block within a page, with an optional one-line "what / when" description. */
@Composable
internal fun CatalogSection(
    title: String,
    description: String? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimension.D500)) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimension.D100)) {
            Text(text = title, typography = AppTheme.typography.Heading.H500)
            if (description != null) {
                Text(
                    text = description,
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.contentTertiary,
                )
            }
        }
        content()
    }
}

/** A hairline divider between major areas in the combined preview. */
@Composable
internal fun CatalogDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AppTheme.colors.border.color),
    )
}

/**
 * One color token as a documentation row: a swatch (with an "Aa" in its on-color), the token name +
 * hex, and a "when / where to use it" note. The ladder of these reads like a spec table.
 */
@Composable
internal fun ColorRow(
    role: String,
    resource: ColorResource,
    onColor: ColorResource?,
    usage: String,
) {
    Row(
        // Fixed width so a ladder of these wraps into columns on a wide canvas (see SwatchFlow)
        // instead of one tall single-file stack.
        modifier = Modifier.width(500.dp),
        horizontalArrangement = Arrangement.spacedBy(Dimension.D600),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(width = 104.dp, height = 60.dp)
                .clip(Radii.Card.shape)
                .background(resource.color)
                .border(1.dp, AppTheme.colors.border.color, Radii.Card.shape)
                .padding(Dimension.D300),
        ) {
            if (onColor != null) {
                Text(text = "Aa", typography = AppTheme.typography.Label.L500, color = onColor)
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Dimension.D100),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimension.D300),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = role, typography = AppTheme.typography.Label.L600)
                Text(
                    text = "${resource.designSystemName} · ${resource.toHexString()}",
                    typography = AppTheme.typography.Caption.C200,
                    color = AppTheme.colors.contentTertiary,
                )
            }
            Text(
                text = usage,
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.contentSecondary,
            )
        }
    }
}

/**
 * A documented component example: a "what / when to use" label + note above the live sample. Use
 * for component variants so each reads as a spec entry, not a loose widget.
 */
@Composable
internal fun SpecRow(
    label: String,
    description: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimension.D400)) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimension.D100)) {
            Text(text = label, typography = AppTheme.typography.Label.L600)
            Text(
                text = description,
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.contentTertiary,
            )
        }
        content()
    }
}

/** One categorical color token as a compact chip (role + name + hex). For grids, not the ladder. */
@Composable
internal fun ColorSwatch(
    role: String,
    resource: ColorResource,
    onColor: ColorResource? = null,
) {
    Column(
        modifier = Modifier.width(150.dp),
        verticalArrangement = Arrangement.spacedBy(Dimension.D200),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .size(width = 150.dp, height = 84.dp)
                .clip(Radii.Card.shape)
                .background(resource.color)
                .border(1.dp, AppTheme.colors.border.color, Radii.Card.shape)
                .padding(Dimension.D400),
        ) {
            if (onColor != null) {
                Text(text = "Aa", typography = AppTheme.typography.Label.L600, color = onColor)
            }
        }
        Text(text = role, typography = AppTheme.typography.Label.L500)
        Text(
            text = resource.designSystemName,
            typography = AppTheme.typography.Caption.C300,
            color = AppTheme.colors.contentSecondary,
        )
        Text(
            text = resource.toHexString(),
            typography = AppTheme.typography.Caption.C200,
            color = AppTheme.colors.contentTertiary,
        )
    }
}

/** A wrapping row of swatches. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SwatchFlow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimension.D500),
        verticalArrangement = Arrangement.spacedBy(Dimension.D500),
    ) { content() }
}
