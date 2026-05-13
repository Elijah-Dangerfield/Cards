package com.dangerfield.cards.libraries.ui.system

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.core.AppState
import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.system.color.Colors
import com.dangerfield.cards.system.typography.Typography
import kotlin.time.Clock

val LocalColors = compositionLocalOf<Colors> {
    error("Theme wasn't applied")
}

val LocalContentColor = compositionLocalOf<ColorResource> {
    error("Theme wasn't applied")
}

val LocalTypography = compositionLocalOf<Typography> {
    error("Theme wasn't applied")
}

val LocalBuildInfo = staticCompositionLocalOf<BuildInfo> {
    error("No LocalBuildInfo provided")
}

val LocalAppState = staticCompositionLocalOf<AppState> {
    error("No LocalAppState provided")
}

val LocalClock = staticCompositionLocalOf<Clock> {
    error("No LocalClock provided")
}