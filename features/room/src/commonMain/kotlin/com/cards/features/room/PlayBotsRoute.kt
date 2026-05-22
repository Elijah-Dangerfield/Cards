package com.dangerfield.cards.features.room

import com.dangerfield.cards.libraries.gameplay.StakeTier
import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.serialization.Serializable

@Serializable
data class PlayBotsRoute(
    val difficulty: String = "Standard",
    val seatCount: Int = 4,
    val stakeTier: String = StakeTier.Default.name,
) : Route()
