package com.dangerfield.cards.libraries.navigation

import kotlinx.serialization.Serializable

@Serializable
class ShakeDialogRoute : Route(
    enter = AnimationType.SlideUp,
    exit = AnimationType.SlideDown,
    popExit = AnimationType.SlideDown,
)
