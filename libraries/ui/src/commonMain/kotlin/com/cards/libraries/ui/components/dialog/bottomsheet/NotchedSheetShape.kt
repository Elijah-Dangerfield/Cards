package com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Bottom-sheet container shape: rounded top corners, plus a half-circle
 * "bulge" notch at top-center, so an icon bubble drag handle can sit
 * **half above** the sheet's regular top edge.
 *
 * Geometry (measured in the sheet's local coordinate space, with y=0 at
 * what would normally be the sheet's top edge):
 *
 * ```
 *       ___                    ← y = -notchRadius (bulge top)
 *      /   \
 * ┌───       ───┐               ← y = 0 (regular top edge)
 * │             │
 * │             │
 * │             │
 * └─────────────┘               ← y = sheet.height (bottom)
 * ```
 *
 * The bulge extends [notchRadius] above y=0, so passing an icon bubble of
 * diameter `2 * notchRadius` positioned at `Modifier.offset(y = -notchRadius)`
 * yields a perfect half-on / half-off bubble: top half lives in the bulge,
 * bottom half lives in the sheet body.
 *
 * Material3's `ModalBottomSheet` accepts any [Shape] for its container.
 * Because the surface's clip uses the shape's outline (not the layout's
 * measured bounds), points above y=0 along the bulge are part of the
 * surface's drawable region. The bubble drawn there is visible.
 *
 * @param cornerRadius Rounded-top-corner radius (matches the sheet style).
 * @param notchRadius Half the icon bubble's diameter — i.e., the radius
 *   of the bulge that lifts above the regular top edge.
 * @param notchCenterFraction Where along the width the bulge sits, 0..1.
 *   Default 0.5f = centered. Override only if you want an off-center icon.
 */
class NotchedSheetShape(
    private val cornerRadius: Dp = 16.dp,
    private val notchRadius: Dp,
    private val notchCenterFraction: Float = 0.5f,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val notchR = with(density) { notchRadius.toPx() }
        val cornerR = with(density) { cornerRadius.toPx() }
            // Clamp so a small sheet width doesn't produce nonsense (corners
            // wider than the top edge would draw inverted arcs).
            .coerceAtMost(size.width / 2f)
        val notchCx = size.width * notchCenterFraction.coerceIn(0f, 1f)
        val w = size.width
        val h = size.height

        // Width along the top edge that the notch takes up.
        val notchHalfWidth = notchR

        val path = Path().apply {
            // ── Top-left corner (rounded) ──
            // Start at the bottom of the top-left arc (along the left edge)
            moveTo(0f, cornerR)
            arcTo(
                rect = Rect(left = 0f, top = 0f, right = 2 * cornerR, bottom = 2 * cornerR),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            )

            // ── Top edge: from end-of-left-corner to start of notch ──
            lineTo(notchCx - notchHalfWidth, 0f)

            // ── Notch: half-circle bulging UP (into negative Y) ──
            // The arc goes from (notchCx - notchR, 0) up over to (notchCx + notchR, 0)
            // The arc's bounding rect is centered horizontally at notchCx,
            // spans vertically from -notchR (top of bulge) to +notchR.
            // Using a sweep of -180° goes counter-clockwise from 180° (left edge
            // of the bounding rect at y=0) to 0° (right edge of the bounding
            // rect at y=0), arcing through 90° (top of the bounding rect at
            // y = -notchR). That's a half-circle pointing up.
            arcTo(
                rect = Rect(
                    left = notchCx - notchR,
                    top = -notchR,
                    right = notchCx + notchR,
                    bottom = notchR,
                ),
                startAngleDegrees = 180f,
                sweepAngleDegrees = -180f,
                forceMoveTo = false,
            )

            // ── Top edge: from end of notch to start of top-right corner ──
            lineTo(w - cornerR, 0f)

            // ── Top-right corner (rounded) ──
            arcTo(
                rect = Rect(left = w - 2 * cornerR, top = 0f, right = w, bottom = 2 * cornerR),
                startAngleDegrees = -90f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            )

            // ── Right edge, bottom edge, left edge ──
            // Bottom corners stay square (this is a bottom sheet; the bottom
            // is off-screen anyway).
            lineTo(w, h)
            lineTo(0f, h)
            lineTo(0f, cornerR)

            close()
        }
        return Outline.Generic(path)
    }
}
