package com.dangerfield.cards.libraries.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

fun Shape.inset(amount: Dp): Shape = inset(
    start = amount,
    top = amount,
    end = amount,
    bottom = amount
)

fun Shape.inset(
    start: Dp = 0.dp,
    top: Dp = 0.dp,
    end: Dp = 0.dp,
    bottom: Dp = 0.dp,
): Shape = ShapeWithInset(delegate = this, start = start, top = top, end = end, bottom = bottom)


operator fun Shape.plus(other: Shape): Shape = CombinedShape(this, other)

private class ShapeWithInset(
    private val delegate: Shape,
    private val start: Dp,
    private val top: Dp,
    private val end: Dp,
    private val bottom: Dp,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        with(density) {
            val start = start.toPx()
            val top = top.toPx()
            val end = end.toPx()
            val bottom = bottom.toPx()
            val insetSize = size.copy(
                width = size.width - start - end,
                height = size.height - top - bottom
            )
            return delegate.createOutline(insetSize, layoutDirection, density).translate(start, top)
        }
}

private class CombinedShape(
    private val first: Shape,
    private val second: Shape,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        first.createOutline(size, layoutDirection, density) + second.createOutline(size, layoutDirection, density)
}

