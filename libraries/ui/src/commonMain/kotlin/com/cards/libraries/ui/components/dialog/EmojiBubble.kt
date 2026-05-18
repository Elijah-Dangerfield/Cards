package com.dangerfield.cards.libraries.ui.components.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.EmojiHandleStyle
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.color.ProvideContentColor

/**
 * Single source of truth for the "emoji bubble" treatment shared by
 * [com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BasicBottomSheet]
 * and [Dialog] — the chunky icon bubble that sits half-on / half-off a
 * modal surface's top edge.
 *
 * Constants in [EmojiBubbleDefaults] are the only place the look and
 * feel is tuned. Everything else (sheet drag-handle, dialog top icon,
 * future modal cousins) calls into this renderer so a single change
 * propagates everywhere.
 */
object EmojiBubbleDefaults {
    /** Bubble diameter (or squircle box size). 60dp. */
    val Size: Dp = Dimension.D1500

    /** Squircle corner radius as a fraction of [Size]. */
    private const val SquircleCornerFraction: Float = 0.30f

    /** Resolve [style] to a concrete [Shape] at the configured [Size]. */
    fun shapeFor(style: EmojiHandleStyle): Shape = when (style) {
        EmojiHandleStyle.Circle -> CircleShape
        EmojiHandleStyle.Squircle -> RoundedCornerShape(Size * SquircleCornerFraction)
    }
}

/**
 * Renders the emoji bubble. INTERNAL — every caller goes through a
 * surface-level API (e.g., `BottomSheetDragHandle.Emoji`,
 * `Dialog(emoji = …)`) which in turn calls this. Don't expose this
 * publicly; if a new surface needs an emoji bubble, route it through
 * here and we keep the look in lockstep.
 *
 * The bubble's fill defaults to [surfaceColor] (the host modal's bg)
 * so the bubble reads as a continuation of the sheet/dialog top edge.
 * That's the "merge into the surface" look we want — the emoji becomes
 * the visual focus, the bubble itself is invisible.
 *
 * Bubble occupies the top of its [Modifier.height] = [EmojiBubbleDefaults.Size]
 * slot. When paired with [com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.NotchedSheetShape]
 * (or its dialog cousin) sized to `Size / 2`, the top half of the
 * bubble sits in the notch and the bottom half sits inside the surface
 * body — the half-on / half-off effect.
 */
@Composable
internal fun EmojiBubble(
    emoji: String,
    style: EmojiHandleStyle,
    surfaceColor: ColorResource,
    contentColor: ColorResource,
    modifier: Modifier = Modifier,
) {
    val size = EmojiBubbleDefaults.Size
    val shape = EmojiBubbleDefaults.shapeFor(style)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(size),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(surfaceColor.color),
            contentAlignment = Alignment.Center,
        ) {
            ProvideContentColor(contentColor) {
                Text(
                    text = emoji,
                    typography = AppTheme.typography.Heading.H800,
                    color = contentColor,
                )
            }
        }
    }
}

/**
 * Convenience: bubble radius = half the bubble's diameter. Use when
 * paired with [com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.NotchedSheetShape]
 * so the notch matches the bubble.
 */
internal val EmojiBubbleNotchRadius: Dp
    get() = EmojiBubbleDefaults.Size / 2
