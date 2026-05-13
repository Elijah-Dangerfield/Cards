package com.dangerfield.cards.system

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.offset
import com.dangerfield.cards.libraries.ui.system.color.ColorResource

inline fun Modifier.thenIf(predicate: Boolean, factory: Modifier.() -> Modifier): Modifier {
    return if (predicate) then(factory(Modifier)) else this
}

inline fun Modifier.then(factory: Modifier.() -> Modifier): Modifier {
    return then(factory(Modifier))
}

inline fun <T : Any> Modifier.thenIfNotNull(
    value: T?,
    factory: Modifier.(T) -> Modifier
): Modifier {
    return if (value != null) then(factory(Modifier, value)) else this
}

fun Modifier.background(
    color: ColorResource,
): Modifier {
    return this.background(color.color)
}

fun Modifier.background(
    color: ColorResource,
    shape: Shape
): Modifier {
    return this.background(color.color, shape)
}

/**
 * Draws circle with a solid [color] behind the content.
 *
 * @param color The color of the circle.
 * @param padding The padding to be applied externally to the circular shape. It determines the spacing between
 * the edge of the circle and the content inside.
 *
 * @return Combined [Modifier] that first draws the background circle and then centers the layout.
 */
fun Modifier.circleBackground(color: ColorResource, padding: Dp): Modifier {
    val realColor = color.color
    val backgroundModifier = drawBehind {
        drawCircle(realColor, size.width / 2f, center = Offset(size.width / 2f, size.height / 2f))
    }

    val layoutModifier = layout { measurable, constraints ->
        // Adjust the constraints by the padding amount
        val adjustedConstraints = constraints.offset(-padding.roundToPx())

        // Measure the composable with the adjusted constraints
        val placeable = measurable.measure(adjustedConstraints)

        // Get the current max dimension to assign width=height
        val currentHeight = placeable.height
        val currentWidth = placeable.width
        val newDiameter = maxOf(currentHeight, currentWidth) + padding.roundToPx() * 2

        // Assign the dimension and the center position
        layout(newDiameter, newDiameter) {
            // Place the composable at the calculated position
            placeable.placeRelative(
                (newDiameter - currentWidth) / 2,
                (newDiameter - currentHeight) / 2
            )
        }
    }

    return this then backgroundModifier then layoutModifier
}

/**
 * Draws chip with a solid [color] behind the content.
 *
 * @param color The color of the chip.
 * @param padding The padding to be applied externally to the chip shape. It determines the spacing between
 * the edge of the chip and the content inside.
 *
 * @return Combined [Modifier] that first draws the background chip and then centers the layout.
 */
fun Modifier.chipBackground(color: ColorResource, padding: Dp): Modifier {
    val realColor = color.color
    val backgroundModifier = drawBehind {
        val chipWidth = size.width
        val chipHeight = size.height
        val radius = size.height / 2f // Assuming the chip has a height-based radius for rounded corners

        // Draw a rounded rectangle (chip shape)
        drawRoundRect(
            color = realColor,
            size = Size(chipWidth, chipHeight),
            cornerRadius = CornerRadius(radius, radius)
        )
    }

    val layoutModifier = layout { measurable, constraints ->
        // Adjust the constraints by the padding amount
        val adjustedConstraints = constraints.offset(-padding.roundToPx())

        // Measure the composable with the adjusted constraints
        val placeable = measurable.measure(adjustedConstraints)

        // Calculate dimensions for the chip shape
        val chipHeight = placeable.height + padding.roundToPx() * 2
        val chipWidth = placeable.width + padding.roundToPx() * 2

        // Assign the dimensions and the center position
        layout(chipWidth, chipHeight) {
            // Place the composable at the calculated position
            placeable.placeRelative(
                padding.roundToPx(),
                padding.roundToPx()
            )
        }
    }

    return this.then(backgroundModifier).then(layoutModifier)
}

fun Modifier.visibility(visible: Boolean): Modifier {
    return layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)

        layout(placeable.width, placeable.height) {
            if (visible) {
                // place this item in the original position
                placeable.placeRelative(0, 0)
            }
        }
    }
}

