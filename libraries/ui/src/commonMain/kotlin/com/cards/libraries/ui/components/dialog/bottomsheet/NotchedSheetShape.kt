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
 * Bottom-sheet container shape: rounded top corners, plus a "bulge" notch
 * at top-center so an icon bubble drag handle can sit **half above** the
 * sheet's regular top edge.
 *
 * The notch is a "half rounded rectangle": half-width = depth =
 * [notchRadius], top corners rounded with [notchCornerRadius]. When
 * `notchCornerRadius == notchRadius` the carve collapses to a half-circle
 * (the circle-bubble case). For a squircle bubble, pass a smaller
 * [notchCornerRadius] so the carve tracks the bubble's flatter top with
 * a uniform gap.
 *
 * Geometry (measured in the sheet's local coordinate space, with y=0 at
 * the top of the layout's measured bounds):
 *
 * ```
 *        ┌─────┐                 ← y = 0          (top of layout / top of bulge)
 *       /       \
 *      |  bulge  |               ← bulge area     (y ∈ [0, notchRadius])
 * ┌────             ────┐        ← y = notchRadius (sheet's regular top edge)
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
 * @param notchRadius Carve half-width (and depth). For a half-circle
 *   notch around a circle bubble this is the bubble's radius plus the
 *   ring gap; for a squircle bubble it's the same — the *corner radius*
 *   is what changes the look.
 * @param notchCornerRadius Top-corner radius of the carve. Defaults to
 *   [notchRadius] which renders a half-circle. Pass a smaller value to
 *   get a squircle-shaped notch. Clamped to `[0, notchRadius]`.
 * @param notchCenterFraction Where along the width the bulge sits, 0..1.
 *   Default 0.5f = centered. Override only if you want an off-center icon.
 * @param bottomCornerRadius Rounded-bottom-corner radius. Defaults to 0
 *   (square corners) because the original use-case is a bottom sheet
 *   whose bottom edge lives off-screen. Dialogs that share this shape
 *   render the whole surface on screen, so they pass a non-zero value
 *   (typically the same as [cornerRadius]) to keep all four corners
 *   consistent.
 */
class NotchedSheetShape(
    private val cornerRadius: Dp = 16.dp,
    private val notchRadius: Dp,
    private val notchCornerRadius: Dp = notchRadius,
    private val notchCenterFraction: Float = 0.5f,
    private val bottomCornerRadius: Dp = 0.dp,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val notchR = with(density) { notchRadius.toPx() }
        val notchCornerR = with(density) { notchCornerRadius.toPx() }
            // Corner radius can't exceed the carve half-width (would
            // produce inverted geometry); also pin to zero floor.
            .coerceIn(0f, notchR)
        val cornerR = with(density) { cornerRadius.toPx() }
            // Clamp so a small sheet width can't produce inverted corners.
            .coerceAtMost(size.width / 2f)
        val bottomCornerR = with(density) { bottomCornerRadius.toPx() }
            .coerceIn(0f, minOf(size.width / 2f, size.height / 2f))
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

            // ── Notch: half rounded rectangle bulging UP from the
            //    regular top edge. Decomposed as:
            //      • short vertical up the left side of the carve
            //      • 90° top-left arc curving into the top edge
            //      • short horizontal along the top of the carve
            //      • 90° top-right arc curving back to vertical
            //      • short vertical down to the regular top edge
            //    When notchCornerR == notchR the two arcs touch at the
            //    apex and the straight segments vanish — the carve
            //    becomes a perfect half-circle (circle-bubble case).
            // Up the left side of the carve to the start of the top-left
            // corner arc.
            lineTo(notchCx - notchR, notchCornerR)
            // Top-left rounded corner of the carve.
            arcTo(
                rect = Rect(
                    left = notchCx - notchR,
                    top = 0f,
                    right = notchCx - notchR + 2 * notchCornerR,
                    bottom = 2 * notchCornerR,
                ),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            )
            // Straight top of the carve.
            lineTo(notchCx + notchR - notchCornerR, 0f)
            // Top-right rounded corner of the carve.
            arcTo(
                rect = Rect(
                    left = notchCx + notchR - 2 * notchCornerR,
                    top = 0f,
                    right = notchCx + notchR,
                    bottom = 2 * notchCornerR,
                ),
                startAngleDegrees = 270f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            )
            // Down the right side of the carve to the regular top edge.
            lineTo(notchCx + notchR, regularTopY)

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
            // Bottom corners default to square (this is a bottom sheet; the
            // bottom is off-screen anyway). Dialogs pass a non-zero
            // [bottomCornerRadius] so all four corners stay consistent
            // when the whole surface is on-screen.
            if (bottomCornerR > 0f) {
                lineTo(w, h - bottomCornerR)
                arcTo(
                    rect = Rect(
                        left = w - 2 * bottomCornerR,
                        top = h - 2 * bottomCornerR,
                        right = w,
                        bottom = h,
                    ),
                    startAngleDegrees = 0f,
                    sweepAngleDegrees = 90f,
                    forceMoveTo = false,
                )
                lineTo(bottomCornerR, h)
                arcTo(
                    rect = Rect(
                        left = 0f,
                        top = h - 2 * bottomCornerR,
                        right = 2 * bottomCornerR,
                        bottom = h,
                    ),
                    startAngleDegrees = 90f,
                    sweepAngleDegrees = 90f,
                    forceMoveTo = false,
                )
                lineTo(0f, regularTopY + cornerR)
            } else {
                lineTo(w, h)
                lineTo(0f, h)
                lineTo(0f, regularTopY + cornerR)
            }

            close()
        }
        return Outline.Generic(path)
    }
}
