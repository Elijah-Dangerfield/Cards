package com.dangerfield.cards.features.room.impl.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp

/**
 * A border whose colour is resolved during **draw**, not composition.
 *
 * `Modifier.border(width, color, shape)` takes an already-resolved [Color], so
 * feeding it an animated value means recomposing every frame just to produce
 * that colour. Taking a lambda instead moves the snapshot read inside the draw
 * scope, so an animation ticking invalidates draw and nothing else.
 *
 * This exists because the table had the same mistake in two places (ENG-49).
 * `PlayerArea`'s turn pulse recomposed the human's name, chip count and hand
 * label 471 times in a 25-second trace; `GoldSeatRing` did the same on every
 * opponent seat, 294 times. Rebuilding that text per frame thrashes Skia's
 * glyph cache and wedges the RenderThread hard enough to ANR — four production
 * traces in `docs/agent/feedback-cases/anr-traces/` all end in
 * `GrTextBlobRedrawCoordinator`.
 *
 * Callers must pass the animation as `State` and read `.value` inside [color],
 * never unwrap it with `by` at the call site — doing that puts the read back in
 * composition and undoes the whole point.
 */
internal fun Modifier.pulsingBorder(
    width: Dp,
    shape: Shape,
    color: () -> Color,
): Modifier = drawWithCache {
    val stroke = width.toPx()
    // Inset by half the stroke so the ring sits inside the bounds, which is
    // where Modifier.border puts it, rather than straddling the edge.
    val inset = shape.createOutline(
        Size(size.width - stroke, size.height - stroke),
        layoutDirection,
        this,
    )
    onDrawWithContent {
        drawContent()
        if (stroke > 0f) {
            translate(left = stroke / 2f, top = stroke / 2f) {
                drawOutline(outline = inset, color = color(), style = Stroke(width = stroke))
            }
        }
    }
}
