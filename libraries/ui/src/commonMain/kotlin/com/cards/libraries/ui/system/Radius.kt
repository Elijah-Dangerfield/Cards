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

/**
 * The corner radius in `dp` for radii backed by an absolute [Dp] corner (all the
 * `R*`/`Button`/`Card` tokens — not [Radii.Round], which is percentage-based).
 * Lets a native surface that takes points (`ASAuthorizationAppleIDButton`,
 * platform sheets) match the Compose radius from the same token instead of a
 * hardcoded literal that drifts when the token changes.
 */
val Radius.cornerRadiusDp: Dp
    get() = with(Density(density = 1f)) { cornerSize.toPx(Size.Unspecified, this).toDp() }

/**
 * Top-corners-only variant of [Radius.shape] for surfaces that meet the bottom
 * screen edge (slide-up sheets, docked bars): rounded on top, square below.
 */
val Radius.topShape: RoundedCornerShape
    get() = RoundedCornerShape(
        topStart = cornerSize,
        topEnd = cornerSize,
        bottomEnd = SquareCornerSize,
        bottomStart = SquareCornerSize,
    )

object Radii {
    val Round = Radius(CornerSize(percent = 50))
    val R300 = Radius(CornerSize(DimensionResource.D300.dp))
    val R400 = Radius(CornerSize(DimensionResource.D400.dp))
    val R500 = Radius(CornerSize(DimensionResource.D500.dp))
    val R600 = Radius(CornerSize(DimensionResource.D600.dp))
    val R700 = Radius(CornerSize(DimensionResource.D700.dp))
    val R750 = Radius(CornerSize(DimensionResource.D750.dp))
    val R800 = Radius(CornerSize(DimensionResource.D800.dp))
    val R850 = Radius(CornerSize(DimensionResource.D850.dp))
    val R900 = Radius(CornerSize(DimensionResource.D900.dp))
    val R1000 = Radius(CornerSize(DimensionResource.D1000.dp))
    val None = Radius(SquareCornerSize)

    val Default get() = None
    val Button get() = R800   // 20dp — chunky, bubbly rect
    val IconButton get() = Round
    val Banner get() = R750   // 18dp
    val Callout get() = R600  // 14dp
    val Sheet get() = R700    // 16dp — slide-up sheet top corners, via [topShape]
    val Header get() = None
    val Card get() = R900     // 24dp — bubbly
}


fun Modifier.clip(radius: Radius): Modifier = clip(radius.shape)

private val SquareCornerSize = CornerSize(0.dp)


