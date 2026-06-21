package com.dangerfield.cards.libraries.ui

import androidx.compose.runtime.Composable
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

/**
 * iOS exposes the setting directly via UIAccessibility. Read at composition
 * time; the rare mid-session toggle (a user backgrounding to flip the switch)
 * re-reads on the next recomposition, which is sufficient for the static-vs-
 * animated radar choice.
 */
@Composable
actual fun isReduceMotionEnabled(): Boolean = UIAccessibilityIsReduceMotionEnabled()
