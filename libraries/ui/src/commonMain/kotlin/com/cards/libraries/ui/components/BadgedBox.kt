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

@Composable
fun BadgedBox(
    badge: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    contentRadius: Radius = Radii.None,
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
            val badgeHorizontalOffset =
                if (badeHasContent) BadgeWithContentHorizontalOffset else BadgeOffset
            val badgeVerticalOffset =
                if (badeHasContent) BadgeWithContentVerticalOffset else BadgeOffset

            anchorPlaceable.placeRelative(0, 0)
            val badgeX = (anchorPlaceable.width + badgeHorizontalOffset.roundToPx()) - cornerCompensationOffset.x.roundToInt()
            val badgeY = (-badgePlaceable.height / 2 + badgeVerticalOffset.roundToPx()) + cornerCompensationOffset.y.roundToInt()

            badgePlaceable.placeRelative(
                badgeX + translationPx.x,
                badgeY + translationPx.y
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
                        backgroundColor = AppTheme.colors.onBackground,
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
                        contentColor = AppTheme.colors.onBackground,
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
                        backgroundColor = AppTheme.colors.onBackground,
                        contentColor = AppTheme.colors.background,
                        padding = Dimension.D100,
                        elevation = Elevation.Button
                    )
                },
                badgeTranslation = DpOffset(x = (-8).dp, y = 4.dp),
                content = {
                    Surface(
                        color = AppTheme.colors.accentPrimary,
                        contentColor = AppTheme.colors.onBackground,
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
                    contentColor = AppTheme.colors.onBackground,
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
                    contentColor = AppTheme.colors.onBackground,
                    contentPadding = PaddingValues(Dimension.D800),
                ) {
                    Text(text = "Offset outward")
                }
            }
        }
    }
}