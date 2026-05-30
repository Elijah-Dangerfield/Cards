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

    // Utility colors — pure black/white + the scrim/shadow alphas.
    object Black : ColorResource(Color(0xFF000000), "black")
    object Black_A70 : ColorResource(Color(0xFF000000).copy(alpha = 0.7f), "black-a-70")
    object Black_A30 : ColorResource(Color(0xFF000000).copy(alpha = 0.3f), "black-a-30")
    object White : ColorResource(Color(0xFFFFFFFF), "white")

    // ── Warm felt theme ──────────────────────────────────────────
    // Espresso neutrals (warm charcoal elevation ladder)
    object Espresso950 : ColorResource(Color(0xFF121110), "espresso-950") // background
    object Espresso900 : ColorResource(Color(0xFF1C1A18), "espresso-900") // surface
    object Espresso850 : ColorResource(Color(0xFF211F1C), "espresso-850") // (spare)
    object Espresso800 : ColorResource(Color(0xFF262320), "espresso-800") // surfaceRaised / surfaceDisabled
    object Espresso700 : ColorResource(Color(0xFF322E2A), "espresso-700") // surfaceHigh

    // Warm-white foreground ramp
    object WarmWhite : ColorResource(Color(0xFFFFFAF4), "warm-white")                            // content
    object WarmWhite_A64 : ColorResource(Color(0xFFFFFAF4).copy(alpha = 0.64f), "warm-white-a-64") // contentSecondary
    object WarmWhite_A44 : ColorResource(Color(0xFFFFFAF4).copy(alpha = 0.44f), "warm-white-a-44") // contentTertiary
    object WarmWhite_A30 : ColorResource(Color(0xFFFFFAF4).copy(alpha = 0.30f), "warm-white-a-30") // contentDisabled

    // Hairlines (warm white, low alpha)
    object Hairline_09 : ColorResource(Color(0xFFFFF8EE).copy(alpha = 0.09f), "hairline-09") // border
    object Hairline_22 : ColorResource(Color(0xFFFFF8EE).copy(alpha = 0.22f), "hairline-22") // borderStrong
    object Hairline_06 : ColorResource(Color(0xFFFFF8EE).copy(alpha = 0.06f), "hairline-06") // borderDisabled

    // Accents (solid / deep for the 3D lip / ink)
    object Gold500 : ColorResource(Color(0xFFE0BC52), "gold-500")
    object Gold800 : ColorResource(Color(0xFF8A6916), "gold-800")
    object GoldInk : ColorResource(Color(0xFF241B07), "gold-ink")
    object Teal500 : ColorResource(Color(0xFF4DBAB0), "teal-500")
    object Teal800 : ColorResource(Color(0xFF2C7E76), "teal-800")
    object TealInk : ColorResource(Color(0xFF08312D), "teal-ink")
    object Coral500 : ColorResource(Color(0xFFFF8A5B), "coral-500")
    object Coral800 : ColorResource(Color(0xFFB85A33), "coral-800")
    object CoralInk : ColorResource(Color(0xFF2A0C0C), "coral-ink")

    // Status (warm variants, suffixed W to avoid clashing with the legacy scale)
    object Blue500W : ColorResource(Color(0xFF3E7BD0), "blue-500-w")   // info
    object BlueInk : ColorResource(Color(0xFF08182E), "blue-ink")
    object Green500W : ColorResource(Color(0xFF5FB67A), "green-500-w") // success
    object GreenInk : ColorResource(Color(0xFF06210F), "green-ink")
    object Amber500W : ColorResource(Color(0xFFE6A23C), "amber-500-w") // warning
    object AmberInk : ColorResource(Color(0xFF2A1C06), "amber-ink")
    object Red500W : ColorResource(Color(0xFFE26B6B), "red-500-w")     // danger
    object Red800W : ColorResource(Color(0xFF7E2A2A), "red-800-w")     // dangerDeep (button lip)
    object RedInk : ColorResource(Color(0xFF2A0C0C), "red-ink")

    // Categorical (NOT accents) — colors.league.*
    object LeagueAmethyst : ColorResource(Color(0xFF8E7CC3), "league-amethyst") // current purple

    // ── Poker · physical-object colors (colors.poker.*) ──────────
    // Theme-independent by design: a chip is gold, a card back is blue, under any theme.
    object PokerChipGold : ColorResource(Color(0xFFE0B863), "poker-chip-gold")
    object PokerChipGoldOutline : ColorResource(Color(0xFF765F2D), "poker-chip-gold-outline")
    object PokerCardWhite : ColorResource(Color(0xFFF4F1E8), "poker-card-white")
    object PokerCardBackBlue : ColorResource(Color(0xFF2E4A9E), "poker-card-back-blue")
    object PokerSeatActive : ColorResource(Color(0xFFFFD66E), "poker-seat-active")
    object PokerBlindRed : ColorResource(Color(0xFFC42E2E), "poker-blind-red")
    object PokerCardSlot : ColorResource(Color(0x14F4F1E8), "poker-card-slot")
    object PokerCardSlotOutline : ColorResource(Color(0x1AFFFFFF), "poker-card-slot-outline")
    object PokerProgressionCyan : ColorResource(Color(0xFF4FC3F7), "poker-progression-cyan")
    object PokerProgressionGreen : ColorResource(Color(0xFF66BB6A), "poker-progression-green")
    object PokerSparkleGold : ColorResource(Color(0xFFE5B946), "poker-sparkle-gold")
    object PokerCoinGradientStart : ColorResource(Color(0xFFFFD66B), "poker-coin-gradient-start")
    object PokerCoinGradientEnd : ColorResource(Color(0xFFD9A933), "poker-coin-gradient-end")
    object PokerCoinOutline : ColorResource(Color(0xFFB68721), "poker-coin-outline")
    object PokerCoinGlyph : ColorResource(Color(0xFF3D2A0A), "poker-coin-glyph")
    object PokerRankBadgePurple : ColorResource(Color(0xFF8E7CC3), "poker-rank-badge-purple")
    object PokerRankBadgePink : ColorResource(Color(0xFFE07AB1), "poker-rank-badge-pink")
    object PokerFeltGreen : ColorResource(Color(0xFF3F5B45), "poker-felt-green")

    // ── Achievement rarity tiers (colors.rarity.*) ───────────────
    // Fixed identity per tier; Epic intentionally reuses the chip gold.
    object RarityCommon : ColorResource(Color(0xFFB08D57), "rarity-common")
    object RarityRare : ColorResource(Color(0xFFB0B0B8), "rarity-rare")
    object RarityLegendary : ColorResource(Color(0xFFE07AB1), "rarity-legendary")

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
    // Utilities
    ColorResource.Black,
    ColorResource.White,
    // Warm felt theme — Espresso neutrals
    ColorResource.Espresso950,
    ColorResource.Espresso900,
    ColorResource.Espresso850,
    ColorResource.Espresso800,
    ColorResource.Espresso700,
    // Warm-white ramp
    ColorResource.WarmWhite,
    ColorResource.WarmWhite_A64,
    ColorResource.WarmWhite_A44,
    ColorResource.WarmWhite_A30,
    // Accents
    ColorResource.Gold500,
    ColorResource.Gold800,
    ColorResource.GoldInk,
    ColorResource.Teal500,
    ColorResource.Teal800,
    ColorResource.TealInk,
    ColorResource.Coral500,
    ColorResource.Coral800,
    ColorResource.CoralInk,
    // Status
    ColorResource.Blue500W,
    ColorResource.Green500W,
    ColorResource.Amber500W,
    ColorResource.Red500W,
    ColorResource.Red800W,
    // Categorical
    ColorResource.LeagueAmethyst,
    ColorResource.PokerChipGold,
    ColorResource.PokerCardBackBlue,
    ColorResource.PokerSeatActive,
    ColorResource.PokerBlindRed,
    ColorResource.PokerRankBadgePurple,
    ColorResource.PokerRankBadgePink,
    ColorResource.PokerFeltGreen,
    ColorResource.RarityCommon,
    ColorResource.RarityRare,
    ColorResource.RarityLegendary,
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