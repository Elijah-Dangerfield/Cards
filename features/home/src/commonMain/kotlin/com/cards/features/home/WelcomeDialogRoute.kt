package com.dangerfield.cards.features.home

import com.dangerfield.cards.libraries.navigation.AnimationType
import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.serialization.Serializable

/**
 * One-shot starter-grant intro shown the first time a fresh-install user
 * lands on home. Routed (not derived state) so it has its own back-stack
 * lifecycle — the home VM fires `OpenWelcomeDialog` exactly once when its
 * gate aligns, the entry point navigates here, and dismissal pops the
 * dialog like any other route.
 *
 * Params are passed eagerly: the dialog doesn't re-fetch the profile or
 * the chip balance. By the time the VM fires the event, those have already
 * resolved, so the dialog can paint on first frame.
 */
@Serializable
data class WelcomeDialogRoute(
    val displayName: String,
    val avatarEmoji: String,
    val avatarBackgroundColorHex: String?,
    val chips: Long,
) : Route(
    enter = AnimationType.SlideUp,
    exit = AnimationType.SlideDown,
    popExit = AnimationType.SlideDown,
)
