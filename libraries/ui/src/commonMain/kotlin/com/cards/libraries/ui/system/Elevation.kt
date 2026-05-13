package com.dangerfield.cards.libraries.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.system.color.ColorResource

@Immutable
class Elevation internal constructor(val dp: Dp) : Comparable<Elevation> {

    override fun compareTo(other: Elevation): Int = dp.compareTo(other.dp)

    companion object {
        val None = Elevation(0.dp)
        val Button = Elevation(3.dp)
        val Card = Elevation(4.dp)

        val Header = Elevation(5.dp)
        val BottomBar = Elevation(10.dp)
        val Modal = Elevation(100.dp)
    }
}

fun Modifier.elevation(
    elevation: Elevation,
    shape: Shape = RectangleShape,
    clip: Boolean = elevation.dp > 0.dp,
    color: ColorResource = ColorResource.Black
): Modifier {
    return this.shadow(
        elevation = elevation.dp,
        shape = shape,
        clip = clip,
        ambientColor = color.color,
        spotColor = color.color
    )
}
