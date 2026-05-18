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
 * the top of the layout's measured bounds):
 *
 * ```
 *           ___                  ← y = 0          (top of layout / top of bulge)
 *          /   \
 *         /     \                ← bulge area     (y ∈ [0, notchRadius])
 * ┌──────         ──────┐        ← y = notchRadius (sheet's regular top edge)
 * │                     │
 * │                     │        ← sheet body     (y ∈ [notchRadius, height])
 * └─────────────────────┘        ← y = height
 * ```
 *
 * Key trick: the bulge lives **inside the layout's measured bounds** (not
 * above them), at the top. The visible "regular sheet top edge" is at
 * `y = notchRadius`, not `y = 0`. That keeps Compose's GraphicsLayer from
 * clipping the bulge — every pixel the user sees lives inside the
 * Surface's measured rectangle, regardless of any ancestor clip behaviour.
 *
 * Pair this shape with a drag-handle slot of `2 * notchRadius` height
 * sitting at the top of the sheet content. The bubble in that slot will
 * appear half-overhanging the sheet edge: top half inside the bulge,
 * bottom half inside the sheet body.
 *
 * @param cornerRadius Rounded-top-corner radius (matches the sheet style).
 * @param notchRadius Half the icon bubble's diameter — also the height
 *   of the bulge above the regular sheet edge.
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
            // Clamp so a small sheet width can't produce inverted corners.
            .coerceAtMost(size.width / 2f)
        val notchCx = size.width * notchCenterFraction.coerceIn(0f, 1f)
        val w = size.width
        val h = size.height

        // The "regular" sheet top edge sits at this y. The bulge fills the
        // band [0, regularTopY] at the notch center; the rest of the top
        // edge is a straight line at regularTopY.
        val regularTopY = notchR

        val path = Path().apply {
            // ── Top-left corner (rounded) ──
            // Start partway down the left edge so the corner arc lands on
            // the regular top line.
            moveTo(0f, regularTopY + cornerR)
            arcTo(
                rect = Rect(
                    left = 0f,
                    top = regularTopY,
                    right = 2 * cornerR,
                    bottom = regularTopY + 2 * cornerR,
                ),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            )

            // ── Top edge: from end-of-left-corner to start of notch ──
            // At y = regularTopY.
            lineTo(notchCx - notchR, regularTopY)

            // ── Notch: half-circle bulging UP from the regular top edge ──
            // The arc's bounding rect spans:
            //   horizontally: notchCx ± notchR
            //   vertically:   y ∈ [0, 2 * notchR]
            // Start at 180° = (notchCx - notchR, notchR) [= regularTopY on the left]
            // Sweep +180° clockwise: passes through 270° = (notchCx, 0) [top of bulge]
            // End at 0° = (notchCx + notchR, notchR) [= regularTopY on the right]
            arcTo(
                rect = Rect(
                    left = notchCx - notchR,
                    top = 0f,
                    right = notchCx + notchR,
                    bottom = 2 * notchR,
                ),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false,
            )

            // ── Top edge: from end of notch to top-right corner ──
            lineTo(w - cornerR, regularTopY)

            // ── Top-right corner (rounded) ──
            arcTo(
                rect = Rect(
                    left = w - 2 * cornerR,
                    top = regularTopY,
                    right = w,
                    bottom = regularTopY + 2 * cornerR,
                ),
                startAngleDegrees = 270f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            )

            // ── Right edge, bottom edge, left edge ──
            // Bottom corners stay square (this is a bottom sheet; the
            // bottom is off-screen anyway).
            lineTo(w, h)
            lineTo(0f, h)
            lineTo(0f, regularTopY + cornerR)

            close()
        }
        return Outline.Generic(path)
    }
}
