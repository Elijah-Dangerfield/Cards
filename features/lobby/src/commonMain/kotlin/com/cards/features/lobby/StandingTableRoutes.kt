package com.dangerfield.cards.features.lobby

import com.dangerfield.cards.libraries.core.AuthRequirement
import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.serialization.Serializable

@Serializable
class StandingTableCreateRoute : Route(authRequirement = AuthRequirement.Account)

@Serializable
data class StandingTableRoute(val code: String) : Route(authRequirement = AuthRequirement.Account)

@Serializable
data class StandingTableEditRoute(val code: String) : Route(authRequirement = AuthRequirement.Account)
