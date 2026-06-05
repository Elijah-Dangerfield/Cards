package com.dangerfield.cards.libraries.ui.system.color

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Steel-blue → deep-navy linear gradient for the guest "Save your progress"
 * sign-in banner shown on Profile, Stats, and Settings. A cool, calm prompt
 * that reads as a system nudge rather than a brand-accent CTA (those stay
 * gold), distinct enough to catch a guest's eye on any tab root.
 *
 * Not a themed token — like [FeatureCardAccents], these are fixed brand
 * swatches that render identically under any future light/dark theme. Per the
 * AGENTS.md rule, the raw `Color(0xFF…)` literals live here under
 * `:libraries:ui/system/color/`, not in the component file.
 */
val SaveProgressGradient: Brush = Brush.linearGradient(
    listOf(Color(0xFF35567F), Color(0xFF1B2F47)),
)
