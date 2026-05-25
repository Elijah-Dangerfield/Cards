package com.dangerfield.cards.system

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed class DimensionResource(val dp: Dp) {
    data object D0 : DimensionResource(0.dp)
    data object D25 : DimensionResource(1.dp)
    data object D50 : DimensionResource(2.dp)
    data object D100 : DimensionResource(4.dp)
    data object D200 : DimensionResource(6.dp)
    data object D300 : DimensionResource(8.dp)
    data object D400 : DimensionResource(10.dp)
    data object D500 : DimensionResource(12.dp)
    data object D600 : DimensionResource(14.dp)
    data object D700 : DimensionResource(16.dp)
    data object D750 : DimensionResource(18.dp)
    data object D800 : DimensionResource(20.dp)
    data object D850 : DimensionResource(22.dp)
    data object D900 : DimensionResource(24.dp)
    data object D950 : DimensionResource(26.dp)
    data object D1000 : DimensionResource(28.dp)
    data object D1050 : DimensionResource(32.dp)
    data object D1100 : DimensionResource(34.dp)
    data object D1150 : DimensionResource(36.dp)
    data object D1200 : DimensionResource(40.dp)
    data object D1250 : DimensionResource(44.dp)
    data object D1300 : DimensionResource(48.dp)
    data object D1350 : DimensionResource(52.dp)
    data object D1400 : DimensionResource(58.dp)
    data object D1450 : DimensionResource(64.dp)
    data object D1500 : DimensionResource(70.dp)
    data object D1600 : DimensionResource(84.dp)
    data object D1700 : DimensionResource(90.dp)
    data object D1800 : DimensionResource(94.dp)
    data object D1900 : DimensionResource(100.dp)

}

object Dimension {
    val D0 = DimensionResource.D0.dp      // 0 dp
    val D25 = DimensionResource.D25.dp    // 1 dp
    val D50 = DimensionResource.D50.dp    // 2 dp
    val D100 = DimensionResource.D100.dp  // 4 dp
    val D200 = DimensionResource.D200.dp  // 6 dp
    val D300 = DimensionResource.D300.dp  // 8 dp
    val D400 = DimensionResource.D400.dp  // 10 dp
    val D500 = DimensionResource.D500.dp  // 12 dp
    val D600 = DimensionResource.D600.dp  // 14 dp
    val D700 = DimensionResource.D700.dp  // 16 dp
    val D750 = DimensionResource.D750.dp  // 18 dp
    val D800 = DimensionResource.D800.dp  // 20 dp
    val D850 = DimensionResource.D850.dp  // 22 dp

    val D900 = DimensionResource.D900.dp  // 24 dp
    val D950 = DimensionResource.D950.dp  // 26 dp
    val D1000 = DimensionResource.D1000.dp // 28 dp
    val D1050 = DimensionResource.D1050.dp // 32 dp
    val D1100 = DimensionResource.D1100.dp // 34 dp
    val D1150 = DimensionResource.D1150.dp // 36 dp
    val D1200 = DimensionResource.D1200.dp // 40 dp
    val D1250 = DimensionResource.D1250.dp // 44 dp
    val D1300 = DimensionResource.D1300.dp // 48 dp
    val D1350 = DimensionResource.D1350.dp // 52 dp
    val D1400 = DimensionResource.D1400.dp // 58 dp
    val D1450 = DimensionResource.D1450.dp // 64 dp
    val D1500 = DimensionResource.D1500.dp // 70 dp
    val D1600 = DimensionResource.D1600.dp // 84 dp
    val D1700 = DimensionResource.D1700.dp // 90 dp
    val D1800 = DimensionResource.D1800.dp // 94 dp
    val D1900 = DimensionResource.D1900.dp // 100 dp


}

/**
 * Typography line-height ratios used throughout the design system.
 * These ratios are applied to font sizes to calculate appropriate line heights.
 * 
 * Ratios are based on typography best practices:
 * - Tight: Minimal spacing for large display text where vertical space is premium
 * - Compact: Reduced spacing for UI elements that need to be space-efficient
 * - Moderate: Balanced spacing for headings that need clear hierarchy
 * - Standard: Good legibility for small text that might be harder to read
 * - Comfortable: Generous spacing for body text to maximize readability
 */
object LineHeightRatio {
    const val TIGHT = 1.1f       // Display text (large, impactful)
    const val COMPACT = 1.2f     // UI labels (buttons, chips, tabs)
    const val MODERATE = 1.25f   // Headings (clear hierarchy)
    const val STANDARD = 1.4f    // Captions (small text legibility)
    const val COMFORTABLE = 1.5f // Body text (reading comfort)
}

/**
 * Extension functions for calculating line heights from dimensions.
 * These functions maintain a single source of truth - the dimension scale.
 * 
 * Usage:
 * ```
 * fontSize = Dimension.D500.sp()
 * lineHeight = Dimension.D500.lineHeight(LineHeightRatio.COMFORTABLE)
 * ```
 */
fun Dp.lineHeight(ratio: Float): TextUnit = (this.value * ratio).sp

fun Dp.sp(): TextUnit = this.value.sp