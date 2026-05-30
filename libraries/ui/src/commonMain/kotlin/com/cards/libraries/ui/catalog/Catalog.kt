package com.dangerfield.cards.libraries.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
 * A browsable, non-phone-shaped tour of every primitive and component. Each `@Preview` in this
 * package is a wide canvas you can open in the IDE preview pane and just *look* at — no app run,
 * no navigation. One page per system area (color, type, radii, buttons, banners, forms).
 *
 * These are intentionally large (widthDp/heightDp set well past a phone) so a whole area fits on
 * screen at once. Keep them here, not scattered across component files, so the catalog stays the
 * single place to review the system.
 */

/** Root for a catalog page: themed background, big title, scrollable column of sections. */
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
            content()
        }
    }
}

/** A titled block within a page. */
@Composable
internal fun CatalogSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimension.D500)) {
        Text(
            text = title,
            typography = AppTheme.typography.Label.L700,
            color = AppTheme.colors.contentSecondary,
        )
        content()
    }
}

/** One color token rendered as a chip with its role, token name, and hex. */
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
