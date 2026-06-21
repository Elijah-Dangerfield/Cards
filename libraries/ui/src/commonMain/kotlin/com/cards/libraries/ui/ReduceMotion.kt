package com.dangerfield.cards.libraries.ui

import androidx.compose.runtime.Composable

/**
 * Reads the platform "reduce motion" accessibility setting.
 *
 * Returns `true` when the user has asked the OS to minimize non-essential
 * animation (iOS Settings → Accessibility → Motion → Reduce Motion; Android
 * Settings → Accessibility → Remove animations / system animation scale 0).
 * DS primitives that play looping or large-travel animations should honor it
 * by rendering a static end-state — [com.dangerfield.cards.libraries.ui.components.room.MatchmakingRadar]
 * is the worked example.
 *
 * Composable so it can observe the live setting where the platform exposes a
 * reactive source; desktop/JVM has no such signal and returns `false`.
 */
@Composable
expect fun isReduceMotionEnabled(): Boolean
