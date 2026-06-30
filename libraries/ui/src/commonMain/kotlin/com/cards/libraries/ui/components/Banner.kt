package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.bounceClick
import com.dangerfield.cards.libraries.ui.components.text.ProvideTextConfig
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.thenIf
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.ui.tooling.preview.Preview
import com.dangerfield.cards.libraries.ui.catalog.BANNER_SUBTITLE
import com.dangerfield.cards.libraries.ui.catalog.BannerCatalogBody
import com.dangerfield.cards.libraries.ui.catalog.CatalogPage

/**
 * Inline, non-modal message. Invents zero colors — each tone is built from the status `subtle`
 * fill + `solid` edge (plus the one gold gradient for [BannerType.Promo]). The "Save your progress"
 * sign-in card is a [BannerType.Trust].
 */
enum class BannerType { Info, Success, Warning, Danger, Promo, Trust }

/**
 * String convenience overload — the common case. Delegates to the slot version, which owns the
 * title / body typography so every banner reads the same.
 *
 * @param leading optional icon / emoji slot (rendered in a tinted 34dp well)
 * @param action  optional trailing slot (e.g. a small Button)
 */
@Composable
fun Banner(
    type: BannerType,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) = Banner(
    type = type,
    modifier = modifier,
    leading = leading,
    action = action,
    onClick = onClick,
    title = { Text(text = title) },
    body = { Text(text = body) },
)

/**
 * Slot version. Like every opinionated DS component, the banner **owns the typography of its
 * slots**: anything in [title] renders in [Label.L600] / [content][Colors.content], anything in
 * [body] renders in [Body.B500] / [contentSecondary][Colors.contentSecondary]. So a bare
 * `Text("…")` in either slot is correct by default. Reach for this overload (over the `String`
 * one) when a slot needs more than a single run of text — an inline icon, a price, a link.
 *
 * @param leading optional icon / emoji slot (rendered in a tinted 34dp well)
 * @param action  optional trailing slot (e.g. a small Button)
 */
@Composable
fun Banner(
    type: BannerType,
    title: @Composable () -> Unit,
    body: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    /** When set, the whole banner is a tap target (with a press bounce) — not
     *  just the trailing [action]. */
    onClick: (() -> Unit)? = null,
) {
    val palette = type.palette()
    val shape = Radii.Banner.shape

    val container = Modifier
        .clip(shape)
        .then(
            when (type) {
                // Promo rides the gold accent gradient. Everything else (incl.
                // Trust, the guest "Save your progress" card) is a flat tinted
                // fill — the gradient read as dated next to the flat surfaces.
                BannerType.Promo -> Modifier.background(AppTheme.colors.accentPrimaryGradient)
                else -> Modifier.background(palette.fill.color)
            }
        )
        .border(1.dp, palette.edge.color, shape)
        .thenIf(onClick != null) { bounceClick(onClick = onClick!!) }
        .padding(horizontal = Dimension.D800, vertical = Dimension.D750)

    Row(
        modifier = modifier.then(container),
        horizontalArrangement = Arrangement.spacedBy(Dimension.D600),
        verticalAlignment = Alignment.Top,
    ) {
        if (leading != null) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.iconWell.color),
                contentAlignment = Alignment.Center,
            ) { leading() }
        }

        Column(modifier = Modifier.weight(1f)) {
            ProvideTextConfig(
                typography = AppTheme.typography.Label.L600,
                color = AppTheme.colors.content,
            ) { title() }
            Box(modifier = Modifier.padding(top = Dimension.D100)) {
                ProvideTextConfig(
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.contentSecondary,
                ) { body() }
            }
        }

        if (action != null) {
            Box(modifier = Modifier.padding(start = Dimension.D500)) { action() }
        }
    }
}

private class BannerPalette(
    val fill: ColorResource,     // ignored for Promo (gradient)
    val edge: ColorResource,
    val iconWell: ColorResource,
)

@Composable
@ReadOnlyComposable
private fun BannerType.palette(): BannerPalette = with(AppTheme.colors) {
    when (this@palette) {
        BannerType.Info -> BannerPalette(infoSubtle, info, infoSubtle)
        BannerType.Success -> BannerPalette(successSubtle, success, successSubtle)
        BannerType.Warning -> BannerPalette(warningSubtle, warning, warningSubtle)
        BannerType.Danger -> BannerPalette(dangerSubtle, danger, dangerSubtle)
        // Promo: gold gradient fill (handled in Banner), gold edge, translucent gold well
        BannerType.Promo -> BannerPalette(accentPrimarySubtle, accentPrimary, accentPrimarySubtle)
        // Trust: a flat cool-blue tint (the "Save your progress" card). A faint
        // white rim + a lighter white well so the lock tile reads as raised.
        BannerType.Trust -> BannerPalette(
            fill = infoSubtle,
            edge = ColorResource.White.withAlpha(0.12f),
            iconWell = ColorResource.White.withAlpha(0.16f),
        )
    }
}

@Preview(widthDp = 900, heightDp = 1500)
@Composable
private fun BannerPreview() {
    CatalogPage(title = "Banner", subtitle = BANNER_SUBTITLE) { BannerCatalogBody() }
}
