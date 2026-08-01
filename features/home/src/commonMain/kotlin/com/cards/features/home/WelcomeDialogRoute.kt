package com.dangerfield.cards.features.home

import com.dangerfield.cards.libraries.navigation.AnimationType
import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.serialization.Serializable

/**
 * The one-time Home welcome dialog. Routed (not derived state) so it has its own
 * back-stack lifecycle — the home VM fires `OpenWelcomeDialog` exactly once when
 * its gate aligns, the entry point navigates here, and dismissal pops the dialog
 * like any other route.
 *
 * The starter-grant reveal is flattened to serializable primitives: [grantChips]
 * is the exact figure to animate (null = none), [grantPending] asks for the
 * "chips landing soon" copy when we couldn't pin a number. [isFounding] layers
 * the founding-member copy and its review / feedback actions on top.
 */
@Serializable
data class WelcomeDialogRoute(
    val displayName: String,
    val avatarEmoji: String,
    val avatarBackgroundColorHex: String?,
    val grantChips: Long?,
    val grantPending: Boolean,
    val isFounding: Boolean,
) : Route(
    enter = AnimationType.SlideUp,
    exit = AnimationType.SlideDown,
    popExit = AnimationType.SlideDown,
)
