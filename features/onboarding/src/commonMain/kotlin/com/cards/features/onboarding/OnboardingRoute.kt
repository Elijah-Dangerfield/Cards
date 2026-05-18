package com.dangerfield.cards.features.onboarding

import com.dangerfield.cards.libraries.navigation.AnimationType
import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.serialization.Serializable

/**
 * Intro pager + identity bootstrap. Only the start destination on first
 * install — once the user taps "Get Started" we set `hasUserOnboarded`,
 * navigate to Home, and never return here unless onboarding is reset
 * (debug-only QA action).
 *
 * Fade transitions because the onboarding → home jump is conceptually a
 * mode switch, not a "drill into" navigation.
 */
@Serializable
class OnboardingRoute : Route(
    enter = AnimationType.FadeIn,
    exit = AnimationType.FadeOut,
    popExit = AnimationType.FadeOut,
)
