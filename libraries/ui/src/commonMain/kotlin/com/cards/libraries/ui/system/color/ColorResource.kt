package com.dangerfield.cards.libraries.ui.system.color

import androidx.compose.animation.VectorConverter
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.animateValueAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD100
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.math.roundToInt

@Suppress("ClassNaming")
@Deprecated("AVOID USING COLOR RESOURCES DIRECTLY. Instead opt for AppTheme.colors.xyz for semantic colors")
@Stable
sealed class ColorResource(val color: Color, val designSystemName: String) {
    object Unspecified : ColorResource(Color.Unspecified, "unspecified")

    // Gray - Neutral scale
    object Gray50 : ColorResource(Color(0xFFFAFAFA), "gray-50")
    object Gray100 : ColorResource(Color(0xFFF5F5F5), "gray-100")
    object Gray200 : ColorResource(Color(0xFFEEEEEE), "gray-200")
    object Gray300 : ColorResource(Color(0xFFE0E0E0), "gray-300")
    object Gray400 : ColorResource(Color(0xFFBDBDBD), "gray-400")
    object Gray500 : ColorResource(Color(0xFF9E9E9E), "gray-500")
    object Gray600 : ColorResource(Color(0xFF757575), "gray-600")
    object Gray700 : ColorResource(Color(0xFF3E3E3E), "gray-700")
    object Gray800 : ColorResource(Color(0xFF2D2D2D), "gray-800")
    object Gray900 : ColorResource(Color(0xFF1A1A1A), "gray-900")

    // Blue - Primary accent
    object Blue50 : ColorResource(Color(0xFFE3F2FD), "blue-50")
    object Blue100 : ColorResource(Color(0xFFBBDEFB), "blue-100")
    object Blue200 : ColorResource(Color(0xFF90CAF9), "blue-200")
    object Blue300 : ColorResource(Color(0xFF64B5F6), "blue-300")
    object Blue400 : ColorResource(Color(0xFF42A5F5), "blue-400")
    object Blue500 : ColorResource(Color(0xFF2196F3), "blue-500")
    object Blue600 : ColorResource(Color(0xFF1E88E5), "blue-600")
    object Blue700 : ColorResource(Color(0xFF1976D2), "blue-700")
    object Blue800 : ColorResource(Color(0xFF1565C0), "blue-800")
    object Blue900 : ColorResource(Color(0xFF0D47A1), "blue-900")

    // Green - Success states
    object Green50 : ColorResource(Color(0xFFE8F5E9), "green-50")
    object Green100 : ColorResource(Color(0xFFC8E6C9), "green-100")
    object Green400 : ColorResource(Color(0xFF66BB6A), "green-400")
    object Green500 : ColorResource(Color(0xFF4CAF50), "green-500")
    object Green600 : ColorResource(Color(0xFF43A047), "green-600")
    object Green700 : ColorResource(Color(0xFF388E3C), "green-700")

    // Red - Error/danger states
    object Red50 : ColorResource(Color(0xFFFFEBEE), "red-50")
    object Red100 : ColorResource(Color(0xFFFFCDD2), "red-100")
    object Red400 : ColorResource(Color(0xFFEF5350), "red-400")
    object Red500 : ColorResource(Color(0xFFF44336), "red-500")
    object Red600 : ColorResource(Color(0xFFE53935), "red-600")
    object Red700 : ColorResource(Color(0xFFD32F2F), "red-700")

    // Orange/Amber - Warning states
    object Orange400 : ColorResource(Color(0xFFFFA726), "orange-400")
    object Orange500 : ColorResource(Color(0xFFFF9800), "orange-500")
    object Orange600 : ColorResource(Color(0xFFFB8C00), "orange-600")
    object Amber500 : ColorResource(Color(0xFFFFC107), "amber-500")
    object Amber600 : ColorResource(Color(0xFFFFB300), "amber-600")

    // Purple - Secondary accent
    object Purple50 : ColorResource(Color(0xFFF3E5F5), "purple-50")
    object Purple100 : ColorResource(Color(0xFFE1BEE7), "purple-100")
    object Purple400 : ColorResource(Color(0xFFAB47BC), "purple-400")
    object Purple500 : ColorResource(Color(0xFF9C27B0), "purple-500")
    object Purple600 : ColorResource(Color(0xFF8E24AA), "purple-600")
    object Purple700 : ColorResource(Color(0xFF7B1FA2), "purple-700")

    // Utility colors
    object Black : ColorResource(Color(0xFF000000), "black")
    object Black_A70 : ColorResource(Color(0xFF000000).copy(alpha = 0.7f), "black-a-70")
    object Black_A30 : ColorResource(Color(0xFF000000).copy(alpha = 0.3f), "black-a-30")
    object Black_A10 : ColorResource(Color(0xFF000000).copy(alpha = 0.1f), "black-a-10")

    object White : ColorResource(Color(0xFFFFFFFF), "white")
    object White_A70 : ColorResource(Color(0xFFFFFFFF).copy(alpha = 0.7f), "white-a-70")
    object White_A30 : ColorResource(Color(0xFFFFFFFF).copy(alpha = 0.3f), "white-a-30")

    class FromColor(color: Color, name: String) : ColorResource(color, name)

    val onColor: ColorResource
        get() {
            return if (color.luminance() > 0.4) Black else White
        }

    fun withAlpha(alpha: Float) = FromColor(this.color.copy(alpha = alpha), this.designSystemName + "_a_${alpha}")
}

@Composable
fun animateColorResourceAsState(
    targetValue: ColorResource,
    animationSpec: AnimationSpec<ColorResource> = spring(),
    label: String = "ColorAnimation",
    finishedListener: ((ColorResource) -> Unit)? = null,
): State<ColorResource> {
    val converter: TwoWayConverter<ColorResource, AnimationVector4D> = remember(targetValue) {
        val colorConverter = (Color.VectorConverter)(targetValue.color.colorSpace)
        TwoWayConverter(convertToVector = { token: ColorResource ->
            colorConverter.convertToVector(token.color)
        }, convertFromVector = { vector ->
            ColorResource.FromColor(
                color = colorConverter.convertFromVector(vector),
                name = targetValue.designSystemName
            )
        })
    }

    return animateValueAsState(
        targetValue = targetValue,
        typeConverter = converter,
        animationSpec = animationSpec,
        label = label,
        finishedListener = finishedListener
    )
}

private val colors = listOf(
    // Gray scale
    ColorResource.Gray50,
    ColorResource.Gray100,
    ColorResource.Gray200,
    ColorResource.Gray300,
    ColorResource.Gray400,
    ColorResource.Gray500,
    ColorResource.Gray600,
    ColorResource.Gray700,
    ColorResource.Gray800,
    ColorResource.Gray900,
    // Blue
    ColorResource.Blue50,
    ColorResource.Blue100,
    ColorResource.Blue200,
    ColorResource.Blue300,
    ColorResource.Blue400,
    ColorResource.Blue500,
    ColorResource.Blue600,
    ColorResource.Blue700,
    ColorResource.Blue800,
    ColorResource.Blue900,
    // Green
    ColorResource.Green50,
    ColorResource.Green100,
    ColorResource.Green400,
    ColorResource.Green500,
    ColorResource.Green600,
    ColorResource.Green700,
    // Red
    ColorResource.Red50,
    ColorResource.Red100,
    ColorResource.Red400,
    ColorResource.Red500,
    ColorResource.Red600,
    ColorResource.Red700,
    // Orange/Amber
    ColorResource.Orange400,
    ColorResource.Orange500,
    ColorResource.Orange600,
    ColorResource.Amber500,
    ColorResource.Amber600,
    // Purple
    ColorResource.Purple50,
    ColorResource.Purple100,
    ColorResource.Purple400,
    ColorResource.Purple500,
    ColorResource.Purple600,
    ColorResource.Purple700,
    // Utilities
    ColorResource.Black,
    ColorResource.White
)

@Preview(widthDp = 2000, heightDp = 10000, showBackground = false)
@Composable
private fun PreviewColorSwatch() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(10)
    ) {
        items(colors) { colorResource ->
            ColorCard(
                colorResource,
                title = colorResource.designSystemName,
                description = colorResource.toHexString()
            )
        }
    }
}


@Composable
internal fun ColorCard(
    colorResource: ColorResource,
    title: String,
    description: String
) {
    Box(
        modifier = Modifier.Companion.padding(Dimension.D100)
            .background(colorResource.color, shape = Radii.Card.shape)
            .height(150.dp)
            .width(120.dp)
            .clip(Radii.Card.shape),
        contentAlignment = Alignment.BottomCenter
    ) {


        Column {
            if (colorResource.color.luminance() > 0.5f) {
                HorizontalDivider(
                    color = Color.DarkGray,
                )
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(Dimension.D500)
            ) {

                Text(
                    text = title,
                    color = Color.Black
                )

                VerticalSpacerD100()

                Text(
                    text = description,
                    color = Color.Black
                )
            }
        }

    }
}

/**
 * Extension function to convert a Color object to a hexadecimal string representation.
 * Includes the alpha value by default but can be omitted.
 *
 * @param includeAlpha whether to include the alpha value in the hex string.
 * @return A hex string representation of the color (e.g., "#FFFFFFFF" or "#FFFFFF" if alpha is omitted).
 */
fun ColorResource.toHexString(includeAlpha: Boolean = true): String {
    val color = this.color

    // Handle unspecified color explicitly
    if (color == Color.Unspecified) return "unspecified"

    fun componentToHex(component: Float): String {
        val intVal = (component * 255f).coerceIn(0f, 255f).roundToInt()
        return intVal.toString(16).uppercase().padStart(2, '0')
    }

    val alpha = if (includeAlpha) componentToHex(color.alpha) else ""
    val red = componentToHex(color.red)
    val green = componentToHex(color.green)
    val blue = componentToHex(color.blue)
    return "#${alpha}${red}${green}${blue}"
}