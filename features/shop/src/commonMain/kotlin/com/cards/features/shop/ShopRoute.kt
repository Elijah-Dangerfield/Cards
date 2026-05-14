package com.dangerfield.cards.features.shop

import com.dangerfield.cards.libraries.navigation.AnimationType
import com.dangerfield.cards.libraries.navigation.Route
import com.dangerfield.cards.libraries.navigation.TabRoute
import kotlinx.serialization.Serializable

@Serializable
class ShopRoute : Route(
    enter = AnimationType.FadeIn,
    exit = AnimationType.FadeOut,
    popExit = AnimationType.FadeOut,
), TabRoute
