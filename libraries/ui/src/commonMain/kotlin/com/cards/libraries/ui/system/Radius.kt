@file:Suppress("MagicNumber")

package com.dangerfield.cards.system

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
class Radius private constructor(val shape: RoundedCornerShape) {
    internal constructor(cornerSize: CornerSize) : this(RoundedCornerShape(cornerSize))

    val cornerSize: CornerSize
        get() = shape.topStart.takeUnless { it == SquareCornerSize }
            ?: shape.topEnd.takeUnless { it == SquareCornerSize }
            ?: shape.bottomEnd.takeUnless { it == SquareCornerSize }
            ?: shape.bottomStart

    override fun equals(other: Any?): Boolean = this === other || other is Radius && shape == other.shape
    override fun hashCode(): Int = shape.hashCode()
    override fun toString(): String = "Radius(cornerSize=$cornerSize)"
}

fun Radius.cornerRadius(density: Density, size: Size): Float {
    return when (val corner = cornerSize) {
        is CornerSize -> {
            // CornerSize can be absolute (Dp) or percentage
            // You need density and size to resolve it
            corner.toPx(size, density)
        }
    }
}

object Radii {
    val Round = Radius(CornerSize(percent = 50))
    val R300 = Radius(CornerSize(DimensionResource.D300.dp))
    val R400 = Radius(CornerSize(DimensionResource.D400.dp))
    val R600 = Radius(CornerSize(DimensionResource.D600.dp))
    val None = Radius(SquareCornerSize)

    val Default get() = None
    val Button get() = Radius(CornerSize(percent = 25))
    val IconButton get() = Round
    val Banner get() = R400
    val Header get() = None
    val Card get() = R400
}


fun Modifier.clip(radius: Radius): Modifier = clip(radius.shape)

private val SquareCornerSize = CornerSize(0.dp)


