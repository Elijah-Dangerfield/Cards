package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import androidx.compose.ui.tooling.preview.Preview

/**
 * A soft, organic "blob" shape filled with a single design-system [color].
 * Intentionally a generic, example decorative accent — drop it behind or
 * beside content (e.g. the Profile "stats & style" banner) to add a warm,
 * playful splash without committing to bespoke art.
 *
 * The shape is a fixed normalized path scaled to whatever size [modifier]
 * imposes, so it stretches to fill its slot. Pass a sizing modifier
 * (e.g. `Modifier.size(96.dp)`) or let a parent constrain it.
 */
@Composable
fun DecorativeBlob(
    modifier: Modifier = Modifier,
    color: ColorResource = AppTheme.colors.accentSecondary,
) {
    val fill = color.color
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // Normalized blob (coordinates in 0..1), scaled to the draw size.
        // Four cubic segments give it an asymmetric, hand-drawn wobble.
        val path = Path().apply {
            moveTo(0.50f * w, 0.04f * h)
            cubicTo(0.80f * w, 0.00f * h, 1.00f * w, 0.22f * h, 0.95f * w, 0.49f * h)
            cubicTo(0.91f * w, 0.76f * h, 0.78f * w, 1.00f * h, 0.50f * w, 0.96f * h)
            cubicTo(0.24f * w, 0.93f * h, 0.02f * w, 0.80f * h, 0.05f * w, 0.50f * h)
            cubicTo(0.07f * w, 0.24f * h, 0.21f * w, 0.07f * h, 0.50f * w, 0.04f * h)
            close()
        }
        drawPath(path = path, color = fill)
    }
}

@Preview
@Composable
private fun DecorativeBlobPreview() {
    PreviewContent {
        DecorativeBlob(modifier = Modifier.size(120.dp))
    }
}
