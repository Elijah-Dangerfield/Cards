package com.dangerfield.cards.libraries.ui

import androidx.compose.runtime.Composable

/**
 * Desktop/JVM has no OS-level reduce-motion signal we can read here, so
 * animations always play. This target is preview / tooling only.
 */
@Composable
actual fun isReduceMotionEnabled(): Boolean = false
