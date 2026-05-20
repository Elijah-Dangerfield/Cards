package com.dangerfield.cards.features.home

import com.dangerfield.cards.libraries.navigation.Route
import com.dangerfield.cards.libraries.navigation.TabRoute
import com.dangerfield.cards.libraries.navigation.AnimationType
import kotlinx.serialization.Serializable

@Serializable
class HomeRoute : Route(
    enter = AnimationType.FadeIn,
    exit = AnimationType.FadeOut,
    popExit = AnimationType.FadeOut,
), TabRoute
