package com.dangerfield.cards.libraries.navigation

import kotlinx.serialization.Serializable

/**
 * Debug-only list of StrictMode violations, reached from the shake menu.
 *
 * A `class`, not a `data object` — see [ShakeDialogRoute]; a `data object`
 * route crashes the iOS navigator at navigate-time.
 */
@Serializable
class PerformanceLogRoute : Route(
    enter = AnimationType.SlideUp,
    exit = AnimationType.SlideDown,
    popExit = AnimationType.SlideDown,
)
