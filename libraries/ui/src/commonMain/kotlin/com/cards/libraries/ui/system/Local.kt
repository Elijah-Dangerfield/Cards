package com.dangerfield.cards.libraries.ui.system

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.cards.DefaultLevelCurve
import com.dangerfield.cards.libraries.cards.LevelCurve
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

/**
 * The XP-per-level curve in force, provided once at the app root from
 * `ProgressionConfig.levelCurve()`. Composable display sites read this instead
 * of calling `levelProgressFor(xp)` with the bundled default so a server-retuned
 * curve makes the shown level agree with the granted one. Defaults to
 * [DefaultLevelCurve] so previews and tests render without a provider.
 */
val LocalLevelCurve = staticCompositionLocalOf<LevelCurve> { DefaultLevelCurve }

/**
 * Degraded guest-account-creation status, provided once at the app root from the
 * `GuestAccountCreator`. Surfaces (Profile, Settings) read this to offer a Retry
 * affordance near their guest sign-in nudge when account setup is still pending
 * after onboarding offline. Carries plain values so `:libraries:ui` keeps no
 * dependency on the identity layer. Defaults to a noop so previews and tests
 * render without a provider.
 */
data class AccountSetupRetryStatus(
    val pending: Boolean = false,
    val isRetrying: Boolean = false,
    val onRetry: () -> Unit = {},
)

val LocalAccountSetupRetry = staticCompositionLocalOf { AccountSetupRetryStatus() }