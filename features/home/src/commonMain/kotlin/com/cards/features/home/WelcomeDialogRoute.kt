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
 * Profile params are passed eagerly. Chips is nullable — the gate fires
 * independently of wallet hydration so a fresh-install user on a slow
 * network still sees the welcome. The dialog falls back to a chip-icon
 * placeholder when chips haven't synced yet; the real balance shows on
 * Home immediately after dismissal.
 */
@Serializable
data class WelcomeDialogRoute(
    val displayName: String,
    val avatarEmoji: String,
    val avatarBackgroundColorHex: String?,
    val chips: Long?,
) : Route(
    enter = AnimationType.SlideUp,
    exit = AnimationType.SlideDown,
    popExit = AnimationType.SlideDown,
)
