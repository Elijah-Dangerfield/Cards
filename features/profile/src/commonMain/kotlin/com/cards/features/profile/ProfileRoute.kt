package com.dangerfield.cards.features.profile

import com.dangerfield.cards.libraries.navigation.AnimationType
import com.dangerfield.cards.libraries.navigation.Route
import com.dangerfield.cards.libraries.navigation.TabRoute
import kotlinx.serialization.Serializable

@Serializable
class ProfileRoute : Route(
    enter = AnimationType.FadeIn,
    exit = AnimationType.FadeOut,
    popExit = AnimationType.FadeOut,
), TabRoute
