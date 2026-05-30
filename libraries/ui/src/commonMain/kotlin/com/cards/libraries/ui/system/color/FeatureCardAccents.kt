package com.dangerfield.cards.libraries.ui.system.color

import androidx.compose.ui.graphics.Color

/**
 * Accent colours for the full-width `FeatureCard` CTAs on Home and other
 * tab roots. Each accent picks a desaturated brand hue so the card reads
 * as the primary tap target without competing with semantic surfaces.
 *
 * These are *not* themed tokens — every screen renders the same green,
 * blue, magenta, gold regardless of dark/light. Centralised here per the
 * AGENTS.md raw-`Color(0xFF…)` rule: raw literals live under
 * `:libraries:ui/system/color/`, not scattered across component files.
 *
 * Pair with [FeatureCard][com.dangerfield.cards.libraries.ui.components.FeatureCard]
 * via `accent = FeatureCardAccents.Green` (etc.).
 */
object FeatureCardAccents {
    val Green: Color = Color(0xFF2D5F4A)
    val Blue: Color = Color(0xFF2D4A6F)
    val Magenta: Color = Color(0xFF6F2D4A)
    val Gold: Color = Color(0xFF6F5F2D)
}
