package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.Radius
import com.dangerfield.cards.system.VerticalSpacerD1600
import com.dangerfield.cards.libraries.ui.Elevation
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.BadgeTokens.LargeLabelTextStyle
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.icon.CircleIcon
import com.dangerfield.cards.libraries.ui.components.icon.IconSize
import com.dangerfield.cards.libraries.ui.components.text.Text
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Where the badge sits relative to the anchor's top-right corner.
 *
 * Picks the badge's positioning anchor (pivot), which is then placed AT
 * the anchor's top-right corner. Picking by pivot makes the placement
 * **deterministic with respect to the badge's measured size** — wider
 * text just produces a wider sticker without callers having to retune
 * any offsets.
 *
 * Use [BadgeTranslation] for fine-tuning OR if you want to bake in a
 * fixed inset (e.g., to keep a long badge from clipping against a
 * surrounding screen padding).
 */
@Stable
sealed interface BadgePlacement {
    /**
     * Material default: small inward inset on both axes (handle is mostly
     * outside the corner but anchored just inside it). Good for notification
     * dots / small numeric badges on bottom-bar icons.
     */
    @Immutable
    object MaterialInset : BadgePlacement

    /**
     * Badge's CENTER lands on the anchor's top-right corner — so the
     * badge half-overhangs in both X and Y. Scales correctly with badge
     * content width: a longer "BEST VALUE" pill produces the same
     * proportional overhang as a short "+20%" pill.
     *
     * The "sticker on the corner of the card" look. Best for shop tiles,
     * achievement chips, and anything where the badge should clearly
     * read as overhanging.
     */
    @Immutable
    object CenterOnCorner : BadgePlacement

    /**
     * Badge's RIGHT EDGE aligns with the anchor's right edge — the badge
     * stays inside the anchor's horizontal bounds, no X overhang, but
     * still half-overhangs on top. Use when ambient padding (screen
     * gutters, narrow rows) makes X overhang risky.
     */
    @Immutable
    object EdgeAlignedTop : BadgePlacement

    /**
     * Free-form pixel offset from [MaterialInset] base. Reach for this
     * only when none of the typed modes fit and the badge geometry is
     * static enough that magic numbers won't drift.
     */
    @Immutable
    data class Translation(val offset: DpOffset) : BadgePlacement
}

@Composable
fun BadgedBox(
    badge: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    contentRadius: Radius = Radii.None,
    placement: BadgePlacement = BadgePlacement.MaterialInset,
    badgeTranslation: DpOffset = DpOffset.Zero,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current

    Layout(
        {
            Box(
                modifier = Modifier.layoutId("anchor"),
                contentAlignment = Alignment.Center,
                content = content
            )
            Box(
                modifier = Modifier.layoutId("badge"),
                content = badge
            )
        },
        modifier = modifier
    ) { measurables, constraints ->

        val badgePlaceable = measurables.first { it.layoutId == "badge" }.measure(
            constraints.copy(minHeight = 0)
        )

        val anchorPlaceable = measurables.first { it.layoutId == "anchor" }.measure(constraints)

        val firstBaseline = anchorPlaceable[FirstBaseline]
        val lastBaseline = anchorPlaceable[LastBaseline]
        val totalWidth = anchorPlaceable.width
        val totalHeight = anchorPlaceable.height

        val cornerCompensationOffset = calculateBadgeOffset(
            cornerSize = contentRadius.cornerSize,
            density = density,
            contentWidth = totalWidth.dp.value,
            contentHeight = totalHeight.dp.value
        )

        val translationPx = with(density) {
            IntOffset(
                badgeTranslation.x.roundToPx(),
                badgeTranslation.y.roundToPx()
            )
        }

        layout(
            totalWidth,
            totalHeight,
            mapOf(
                FirstBaseline to firstBaseline,
                LastBaseline to lastBaseline
            )
        ) {

            val badeHasContent = badgePlaceable.width > (BadgeTokens.Size.roundToPx())

            anchorPlaceable.placeRelative(0, 0)

            // Resolve the typed placement to a (left edge, top edge)
            // coordinate for the badge. Everything is computed against
            // measured `badgePlaceable.width / height`, so geometry stays
            // correct as badge content grows.
            val (baseX, baseY) = when (placement) {
                is BadgePlacement.MaterialInset -> {
                    val hOffsetPx =
                        if (badeHasContent) BadgeWithContentHorizontalOffset.roundToPx() else BadgeOffset.roundToPx()
                    val vOffsetPx =
                        if (badeHasContent) BadgeWithContentVerticalOffset.roundToPx() else BadgeOffset.roundToPx()
                    val x = (anchorPlaceable.width + hOffsetPx) - cornerCompensationOffset.x.roundToInt()
                    val y = (-badgePlaceable.height / 2 + vOffsetPx) + cornerCompensationOffset.y.roundToInt()
                    x to y
                }

                is BadgePlacement.CenterOnCorner -> {
                    // Badge center on the corner: shift left by half-width
                    // (so center.x lands on anchor.width) and up by
                    // half-height (so center.y lands on the anchor top).
                    val x = anchorPlaceable.width - badgePlaceable.width / 2
                    val y = -badgePlaceable.height / 2
                    x to y
                }

                is BadgePlacement.EdgeAlignedTop -> {
                    // Badge right edge aligned with anchor right edge
                    // (no X overhang), badge center on anchor top
                    // (half Y overhang).
                    val x = anchorPlaceable.width - badgePlaceable.width
                    val y = -badgePlaceable.height / 2
                    x to y
                }

                is BadgePlacement.Translation -> {
                    val hOffsetPx = placement.offset.x.roundToPx()
                    val vOffsetPx = placement.offset.y.roundToPx()
                    val x = (anchorPlaceable.width + hOffsetPx) - cornerCompensationOffset.x.roundToInt()
                    val y = (-badgePlaceable.height / 2 + vOffsetPx) + cornerCompensationOffset.y.roundToInt()
                    x to y
                }
            }

            badgePlaceable.placeRelative(
                baseX + translationPx.x,
                baseY + translationPx.y,
            )
        }
    }
}

private fun calculateBadgeOffset(
    cornerSize: CornerSize,
    density: Density,
    contentWidth: Float,
    contentHeight: Float
): Offset {
    val cornerSizepx = cornerSize.toPx(Size(contentWidth, contentHeight), density)

    val radius = cornerSizepx

    val offset = radius - (radius * sqrt(2.0f) / 2)

    return Offset(offset, offset)
}

@ExperimentalMaterial3Api
@Composable
fun Badge(
    modifier: Modifier = Modifier,
    containerColor: Color = BadgeDefaults.containerColor,
    contentColor: Color = contentColorFor(containerColor),
    content: @Composable (RowScope.() -> Unit)? = null,
) {
    val size = if (content != null) BadgeTokens.LargeSize else BadgeTokens.Size
    val shape = if (content != null) {
        BadgeTokens.LargeShape
    } else {
        BadgeTokens.Shape
    }

    // Draw badge container.
    Row(
        modifier = modifier
            .defaultMinSize(minWidth = size, minHeight = size)
            .background(
                color = containerColor,
                shape = shape
            )
            .clip(shape)
            .then(
                if (content != null)
                    Modifier.padding(horizontal = BadgeWithContentHorizontalPadding) else Modifier
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (content != null) {
            // Not using Surface composable because it blocks touch propagation behind it.
            CompositionLocalProvider(
                LocalContentColor provides contentColor
            ) {

                val style = LargeLabelTextStyle

                ProvideTextStyle(
                    value = style,
                    content = { content() }
                )
            }
        }
    }
}

/** Default values used for [Badge] implementations. */
@ExperimentalMaterial3Api
object BadgeDefaults {
    /** Default container color for a badge. */
    val containerColor: Color @Composable get() = AppTheme.colors.accentPrimary.color
}

internal val BadgeWithContentHorizontalPadding = 4.dp
internal val BadgeWithContentHorizontalOffset = -4.dp
internal val BadgeWithContentVerticalOffset = -4.dp
internal val BadgeOffset = 0.dp

internal object BadgeTokens {
    val LargeLabelTextStyle: TextStyle @Composable get() = AppTheme.typography.Label.L600.style
    val LargeShape = Radii.Round.shape
    val LargeSize = 16.0.dp
    val Shape = Radii.Round.shape
    val Size = 6.0.dp
}

@Preview(widthDp = 300, heightDp = 300)
@Composable
private fun PreviewBadgedBox() {
    PreviewContent {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            val radius = Radii.Card

            BadgedBox(
                badge = {
                    CircleIcon(
                        icon = Icons.Check("Close"),
                        iconSize = IconSize.Small,
                        backgroundColor = AppTheme.colors.content,
                        contentColor = AppTheme.colors.background,
                        padding = Dimension.D100,
                        elevation = Elevation.Button
                    )
                },
                contentRadius = radius,
                content = {
                    Surface(
                        radius = radius,
                        color = AppTheme.colors.accentPrimary,
                        contentColor = AppTheme.colors.content,
                        contentPadding = PaddingValues(Dimension.D800),
                    ) {
                        Text(text = "Rounded Edges")
                    }
                }
            )

            VerticalSpacerD1600()

            BadgedBox(
                badge = {
                    CircleIcon(
                        icon = Icons.Check(""),
                        iconSize = IconSize.Small,
                        backgroundColor = AppTheme.colors.content,
                        contentColor = AppTheme.colors.background,
                        padding = Dimension.D100,
                        elevation = Elevation.Button
                    )
                },
                badgeTranslation = DpOffset(x = (-8).dp, y = 4.dp),
                content = {
                    Surface(
                        color = AppTheme.colors.accentPrimary,
                        contentColor = AppTheme.colors.content,
                        contentPadding = PaddingValues(Dimension.D800),
                    ) {
                        Text(text = "Non Rounded")
                    }
                }
            )
        }
    }
}

@Preview(widthDp = 320, heightDp = 300)
@Composable
private fun PreviewBadgedBoxTranslations() {
    val radius = Radii.Card

    PreviewContent {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Dimension.D1000),
            verticalArrangement = Arrangement.spacedBy(Dimension.D800)
        ) {
            Text(text = "Badge nudged down/right")
            BadgedBox(
                badge = {
                    Badge {
                        Text(text = "3")
                    }
                },
                badgeTranslation = DpOffset(x = -6.dp, y = 6.dp)
            ) {
                Surface(
                    radius = radius,
                    color = AppTheme.colors.accentPrimary,
                    contentColor = AppTheme.colors.content,
                    contentPadding = PaddingValues(Dimension.D800),
                ) {
                    Text(text = "Offset inward")
                }
            }

            Text(text = "Badge nudged up/left")
            BadgedBox(
                badge = {
                    Badge {
                        Text(text = "12")
                    }
                },
                badgeTranslation = DpOffset(x = (-8).dp, y = (-4).dp)
            ) {
                Surface(
                    radius = radius,
                    color = AppTheme.colors.accentPrimary,
                    contentColor = AppTheme.colors.content,
                    contentPadding = PaddingValues(Dimension.D800),
                ) {
                    Text(text = "Offset outward")
                }
            }
        }
    }
}