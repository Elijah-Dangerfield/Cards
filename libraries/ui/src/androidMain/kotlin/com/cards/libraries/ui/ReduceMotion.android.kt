package com.dangerfield.cards.libraries.ui

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Android maps "Remove animations" (and the developer-options animation
 * scales) onto [Settings.Global.TRANSITION_ANIMATION_SCALE]; a value of 0
 * means the user wants motion suppressed. Reading the global setting is the
 * platform-standard reduce-motion probe — there's no dedicated flag.
 */
@Composable
actual fun isReduceMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    val scale = Settings.Global.getFloat(
        resolver,
        Settings.Global.TRANSITION_ANIMATION_SCALE,
        1f,
    )
    return scale == 0f
}
