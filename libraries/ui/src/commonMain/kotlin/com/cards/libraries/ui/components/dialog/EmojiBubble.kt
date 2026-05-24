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
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.EmojiHandleStyle
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.color.ProvideContentColor
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Single source of truth for the "emoji bubble" treatment shared by
 * [com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet]
 * and [Dialog] — the chunky icon bubble that sits half-on / half-off a
 * modal surface's top edge.
 *
 * Constants in [EmojiBubbleDefaults] are the only place the look and
 * feel is tuned. Everything else (sheet drag-handle, dialog top icon,
 * future modal cousins) calls into this renderer so a single change
 * propagates everywhere.
 */
object EmojiBubbleDefaults {
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
     * Fraction of the bubble's diameter used as breathing room below the
     * bubble. At the current 100dp [Size] this resolves to 10dp (D400),
     * preserving the historic gap. Tied to [Size] so any future caller
     * that overrides the bubble size sees the gap scale with it instead
     * of inheriting a hand-tuned 10dp that drifts apart from the bubble.
     */
    private const val BodyGapFraction: Float = 0.10f

    /**
     * Minimum gap below the bubble. Prevents the proportional gap from
     * collapsing for small bubbles where 10% would crowd the content.
     */
    private val BodyGapMin: Dp = Dimension.D300

    /**
     * Breathing room baked **into the bubble's slot**, below the bubble.
     * Scales proportionally with [size] (see [BodyGapFraction]) with a
     * [BodyGapMin] floor. Callers (sheet, dialog, future modals) just stack
     * content after the bubble — the spacing comes for free, so any change
     * to bubble metrics propagates without every caller having to know the
     * geometry.
     */
    fun bodyGapFor(size: Dp): Dp = (size * BodyGapFraction).coerceAtLeast(BodyGapMin)

    /** Squircle corner radius as a fraction of [Size]. */
    private const val SquircleCornerFraction: Float = 0.30f

    /**
     * Bubble's own corner radius for the given [style]. Used both to clip
     * the bubble itself and to derive the notch corner radius so the
     * carve-out matches the bubble shape (no circular notch around a
     * squircle bubble).
     */
    fun bubbleCornerRadiusFor(style: EmojiHandleStyle): Dp = when (style) {
        EmojiHandleStyle.Circle -> Size / 2
        EmojiHandleStyle.Squircle -> Size * SquircleCornerFraction
    }

    /**
     * Corner radius for the host surface's notch carve-out. The carve is
     * always a half rounded rectangle of half-width = [EmojiBubbleNotchRadius];
     * setting the corner radius equal to that half-width collapses it
     * back to a half-circle (the circle case). Smaller corner radius
     * gives the squircle look — straight top with rounded corners that
     * track the bubble's own corners with a uniform [RingWidth] gap.
     */
    fun notchCornerRadiusFor(style: EmojiHandleStyle): Dp =
        bubbleCornerRadiusFor(style) + RingWidth

    /** Resolve [style] to a concrete [Shape] at the configured [Size]. */
    fun shapeFor(style: EmojiHandleStyle): Shape = when (style) {
        EmojiHandleStyle.Circle -> CircleShape
        EmojiHandleStyle.Squircle -> RoundedCornerShape(bubbleCornerRadiusFor(style))
    }
}

/**
 * Renders the emoji bubble. INTERNAL — every caller goes through a
 * surface-level API (e.g., `BottomSheetDragHandle.Emoji`,
 * `Dialog(emoji = …)`) which in turn calls this. Don't expose this
 * publicly; if a new surface needs an emoji bubble, route it through
 * here and we keep the look in lockstep.
 *
 * The bubble's fill is supplied as a [BubbleSurface] — either a themed
 * [BubbleSurface.Solid] (typical) or an arbitrary [BubbleSurface.Gradient]
 * brush (commerce / featured surfaces that need to mirror a gradient
 * already used elsewhere — e.g., the shop's featured-pack card backdrop).
 *
 * Geometry: the slot is `Size + RingWidth + BodyGap` tall. The bubble
 * (diameter [EmojiBubbleDefaults.Size]) is inset from the slot top by
 * [EmojiBubbleDefaults.RingWidth] so its center sits exactly at
 * [EmojiBubbleNotchRadius] below the slot top — i.e., on the host
 * surface's "regular top edge" when paired with
 * [com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.NotchedSheetShape]
 * sized to that same [EmojiBubbleNotchRadius]. The notch is wider than
 * the bubble by [EmojiBubbleDefaults.RingWidth] on every side, so a thin
 * ring of scrim shows around the top half — the "outline halo" look. The
 * extra gap at the bottom of the slot (see [EmojiBubbleDefaults.bodyGapFor])
 * gives any caller automatic breathing room before its first content row,
 * scaled proportionally with the bubble size — no caller-side math needed.
 *
 * [emoji] must be non-empty; empty strings would render an invisible
 * bubble (no glanceable cue) which is almost certainly a bug at the call
 * site rather than a legitimate use case.
 */
@Composable
internal fun EmojiBubble(
    emoji: String,
    style: EmojiHandleStyle,
    surface: BubbleSurface,
    contentColor: ColorResource,
    modifier: Modifier = Modifier,
) {
    require(emoji.isNotEmpty()) { "EmojiBubble emoji must be non-empty" }

    val size = EmojiBubbleDefaults.Size
    val ringWidth = EmojiBubbleDefaults.RingWidth
    val bodyGap = EmojiBubbleDefaults.bodyGapFor(size)
    val shape = EmojiBubbleDefaults.shapeFor(style)
    val brush: Brush = when (surface) {
        is BubbleSurface.Solid -> SolidColor(surface.color.color.copy(alpha = surface.alpha))
        is BubbleSurface.Gradient -> surface.brush
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
                Text(
                    text = emoji,
                    typography = AppTheme.typography.Heading.H1100,
                    color = contentColor,
                )
            }
        }
    }
}

/**
 * Fill for an emoji bubble. Two flavours:
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
 * [EmojiBubble]'s renderer.
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
 * Notch radius for the surface shape that hosts an emoji bubble.
 * Slightly larger than the bubble's own radius by
 * [EmojiBubbleDefaults.RingWidth] so a thin scrim halo shows around the
 * bubble's top half — see [EmojiBubble] for the geometry.
 */
internal val EmojiBubbleNotchRadius: Dp
    get() = EmojiBubbleDefaults.Size / 2 + EmojiBubbleDefaults.RingWidth

// ---------------------------------------------------------------------------
// Previews — eyeball both shapes against a matching surface and against an
// accent surface so the "bubble pops off the sheet" override is visible.
// ---------------------------------------------------------------------------

@Preview
@Composable
private fun PreviewEmojiBubble_CircleMatchingSurface() {
    PreviewContent {
        EmojiBubble(
            emoji = "🎉",
            style = EmojiHandleStyle.Circle,
            surface = BubbleSurface.Solid(AppTheme.colors.surfacePrimary),
            contentColor = AppTheme.colors.onSurfacePrimary,
        )
    }
}

@Preview
@Composable
private fun PreviewEmojiBubble_SquircleMatchingSurface() {
    PreviewContent {
        EmojiBubble(
            emoji = "💃",
            style = EmojiHandleStyle.Squircle,
            surface = BubbleSurface.Solid(AppTheme.colors.surfacePrimary),
            contentColor = AppTheme.colors.onSurfacePrimary,
        )
    }
}

@Preview
@Composable
private fun PreviewEmojiBubble_CircleAccent() {
    PreviewContent {
        EmojiBubble(
            emoji = "$",
            style = EmojiHandleStyle.Circle,
            surface = BubbleSurface.Solid(AppTheme.colors.accentPrimary),
            contentColor = AppTheme.colors.onAccentPrimary,
        )
    }
}

@Preview
@Composable
private fun PreviewEmojiBubble_SquircleAccent() {
    PreviewContent {
        EmojiBubble(
            emoji = "🎰",
            style = EmojiHandleStyle.Squircle,
            surface = BubbleSurface.Solid(AppTheme.colors.accentPrimary),
            contentColor = AppTheme.colors.onAccentPrimary,
        )
    }
}
