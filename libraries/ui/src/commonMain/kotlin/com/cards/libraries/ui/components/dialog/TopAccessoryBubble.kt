package com.dangerfield.cards.libraries.ui.components.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.icon.Icon
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.color.ProvideContentColor
import com.dangerfield.cards.system.typography.TypographyResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Geometry + shape constants for the "bubble that overhangs a modal's
 * top edge" affordance, shared by [Dialog] and
 * [com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet].
 *
 * Single source of truth — any tuning happens here and propagates to
 * every surface that hosts a [TopAccessory].
 */
object TopAccessoryDefaults {
    /** Bubble diameter (or squircle box size). 100dp. */
    val Size: Dp = Dimension.D1900

    /**
     * Visible gap between the bubble and the surrounding notch carve-out.
     * The notch is sized slightly larger than the bubble so a thin outline
     * of scrim shows around the top half — reads as a glowing halo, makes
     * the bubble look "set into" the sheet/dialog rather than glued onto
     * its top edge.
     */
    val RingWidth: Dp = Dimension.D200

    /**
     * Breathing room baked **into the bubble's slot**, below the bubble.
     * Callers (sheet, dialog, future modals) just stack content after the
     * bubble — the spacing comes for free, so any change to bubble metrics
     * propagates without every caller having to know the geometry.
     */
    val BodyGap: Dp = Dimension.D300

    /** Squircle corner radius as a fraction of [Size]. */
    private const val SquircleCornerFraction: Float = 0.30f

    /**
     * Bubble's own corner radius for the given [style]. Used both to clip
     * the bubble itself and to derive the notch corner radius so the
     * carve-out matches the bubble shape (no circular notch around a
     * squircle bubble).
     */
    fun bubbleCornerRadiusFor(style: AccessoryShape): Dp = when (style) {
        AccessoryShape.Circle -> Size / 2
        AccessoryShape.Squircle -> Size * SquircleCornerFraction
    }

    /**
     * Corner radius for the host surface's notch carve-out. The carve is
     * always a half rounded rectangle of half-width = [TopAccessoryNotchRadius];
     * setting the corner radius equal to that half-width collapses it
     * back to a half-circle (the circle case). Smaller corner radius
     * gives the squircle look — straight top with rounded corners that
     * track the bubble's own corners with a uniform [RingWidth] gap.
     */
    fun notchCornerRadiusFor(style: AccessoryShape): Dp =
        bubbleCornerRadiusFor(style) + RingWidth

    /** Resolve [style] to a concrete [Shape] at the configured [Size]. */
    fun shapeFor(style: AccessoryShape): Shape = when (style) {
        AccessoryShape.Circle -> CircleShape
        AccessoryShape.Squircle -> RoundedCornerShape(bubbleCornerRadiusFor(style))
    }
}

/**
 * Renders the top-accessory bubble for a [TopAccessory]. INTERNAL — every
 * caller goes through a surface-level API (`Dialog(topAccessory = …)`,
 * `BottomSheetDragHandle.Accessory(…)`) which in turn calls this. Don't
 * expose it publicly; if a new surface needs the same affordance, route
 * it through here so the look stays in lockstep.
 *
 * The bubble's fill comes from [TopAccessory.surface] when non-null, or
 * [fallbackSurface] (the host surface's choice — usually the sheet/dialog
 * background) for a seamless top edge.
 *
 * Geometry: the slot is `Size + RingWidth + BodyGap` tall. The bubble
 * (diameter [TopAccessoryDefaults.Size]) is inset from the slot top by
 * [TopAccessoryDefaults.RingWidth] so its center sits exactly at
 * [TopAccessoryNotchRadius] below the slot top — i.e., on the host
 * surface's "regular top edge" when paired with
 * [com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.NotchedSheetShape]
 * sized to that same [TopAccessoryNotchRadius]. The notch is wider than
 * the bubble by [TopAccessoryDefaults.RingWidth] on every side, so a thin
 * ring of scrim shows around the top half — the "outline halo" look. The
 * extra [TopAccessoryDefaults.BodyGap] at the bottom of the slot gives
 * any caller automatic breathing room before its first content row, with
 * no caller-side math needed.
 */
@Composable
internal fun TopAccessoryBubble(
    accessory: TopAccessory,
    fallbackSurface: BubbleSurface,
    contentColor: ColorResource,
    emojiTypography: TypographyResource = AppTheme.typography.Heading.H1100,
    modifier: Modifier = Modifier,
) {
    val size = TopAccessoryDefaults.Size
    val ringWidth = TopAccessoryDefaults.RingWidth
    val bodyGap = TopAccessoryDefaults.BodyGap
    val shape = TopAccessoryDefaults.shapeFor(accessory.style)
    val resolvedSurface = accessory.surface ?: fallbackSurface
    val brush: Brush = when (resolvedSurface) {
        is BubbleSurface.Solid -> SolidColor(resolvedSurface.color.color.copy(alpha = resolvedSurface.alpha))
        is BubbleSurface.Gradient -> resolvedSurface.brush
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(size + ringWidth + bodyGap),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .padding(top = ringWidth)
                .size(size)
                .clip(shape)
                .background(brush),
            contentAlignment = Alignment.Center,
        ) {
            ProvideContentColor(contentColor) {
                when (accessory) {
                    is TopAccessory.Emoji -> {
                        require(accessory.emoji.isNotEmpty()) {
                            "TopAccessory.Emoji emoji must be non-empty"
                        }
                        Text(
                            text = accessory.emoji,
                            typography = emojiTypography,
                            color = contentColor,
                        )
                    }
                    is TopAccessory.Icon -> Icon(
                        icon = accessory.icon,
                        size = accessory.iconSize,
                        color = accessory.iconColor ?: contentColor,
                    )
                    is TopAccessory.Custom -> accessory.render()
                }
            }
        }
    }
}

/**
 * Fill for a top-accessory bubble. Two flavours:
 *
 *  - [Solid] — themed [ColorResource]. Default path; reads as
 *    "continuation of the host surface" when matched to the sheet/dialog
 *    background, or as a colored badge when set to an accent token.
 *  - [Gradient] — arbitrary [Brush]. For surfaces that match a gradient
 *    elsewhere in the UI (e.g., a featured product card whose backdrop
 *    is a gradient — the purchase sheet's bubble can carry the same
 *    brush so the visual line from card to sheet is preserved).
 *
 * Sealed so we can grow it (image, animated gradient) without churning
 * every call site, but every variant resolves to a [Brush] inside
 * [TopAccessoryBubble]'s renderer.
 */
@Immutable
sealed interface BubbleSurface {
    /**
     * Themed [color], optionally rendered at reduced [alpha] for tinted
     * "soft badge" looks. Defaults to fully opaque. Use alpha for surfaces
     * that mirror an existing in-app tinted treatment (e.g., the shop's
     * grid-card icon tiles are accent/gold at 0.18 alpha — the matching
     * purchase-sheet bubble passes the same color and alpha here).
     */
    @Immutable
    data class Solid(
        val color: ColorResource,
        val alpha: Float = 1f,
    ) : BubbleSurface

    /** Arbitrary [brush] — gradients, linear or radial. */
    @Immutable
    data class Gradient(val brush: Brush) : BubbleSurface
}

/**
 * Notch radius for the surface shape that hosts a top-accessory bubble.
 * Slightly larger than the bubble's own radius by
 * [TopAccessoryDefaults.RingWidth] so a thin scrim halo shows around the
 * bubble's top half — see [TopAccessoryBubble] for the geometry.
 */
internal val TopAccessoryNotchRadius: Dp
    get() = TopAccessoryDefaults.Size / 2 + TopAccessoryDefaults.RingWidth

// ---------------------------------------------------------------------------
// Previews — eyeball every TopAccessory variant against a matching surface
// and against an accent surface so the "bubble pops off the sheet" override
// is visible.
// ---------------------------------------------------------------------------

@Preview
@Composable
private fun PreviewTopAccessoryBubble_Emoji_Circle() {
    PreviewContent {
        TopAccessoryBubble(
            accessory = TopAccessory.Emoji(emoji = "🎉"),
            fallbackSurface = BubbleSurface.Solid(AppTheme.colors.surfacePrimary),
            contentColor = AppTheme.colors.onSurfacePrimary,
        )
    }
}

@Preview
@Composable
private fun PreviewTopAccessoryBubble_Emoji_Squircle() {
    PreviewContent {
        TopAccessoryBubble(
            accessory = TopAccessory.Emoji(
                emoji = "💃",
                style = AccessoryShape.Squircle,
            ),
            fallbackSurface = BubbleSurface.Solid(AppTheme.colors.surfacePrimary),
            contentColor = AppTheme.colors.onSurfacePrimary,
        )
    }
}

@Preview
@Composable
private fun PreviewTopAccessoryBubble_Emoji_AccentSurface() {
    PreviewContent {
        TopAccessoryBubble(
            accessory = TopAccessory.Emoji(
                emoji = "$",
                surface = BubbleSurface.Solid(AppTheme.colors.accentPrimary),
            ),
            fallbackSurface = BubbleSurface.Solid(AppTheme.colors.surfacePrimary),
            contentColor = AppTheme.colors.onAccentPrimary,
        )
    }
}

@Preview
@Composable
private fun PreviewTopAccessoryBubble_Icon_Circle() {
    PreviewContent {
        TopAccessoryBubble(
            accessory = TopAccessory.Icon(
                icon = Icons.Check("check"),
                surface = BubbleSurface.Solid(AppTheme.colors.accentPrimary),
            ),
            fallbackSurface = BubbleSurface.Solid(AppTheme.colors.surfacePrimary),
            contentColor = AppTheme.colors.onAccentPrimary,
        )
    }
}

@Preview
@Composable
private fun PreviewTopAccessoryBubble_Custom() {
    PreviewContent {
        TopAccessoryBubble(
            accessory = TopAccessory.Custom(
                style = AccessoryShape.Squircle,
                surface = BubbleSurface.Solid(AppTheme.colors.surfaceTertiary),
                render = {
                    Text(
                        text = "🃏♣",
                        typography = AppTheme.typography.Heading.H900,
                    )
                },
            ),
            fallbackSurface = BubbleSurface.Solid(AppTheme.colors.surfacePrimary),
            contentColor = AppTheme.colors.onSurfacePrimary,
        )
    }
}
