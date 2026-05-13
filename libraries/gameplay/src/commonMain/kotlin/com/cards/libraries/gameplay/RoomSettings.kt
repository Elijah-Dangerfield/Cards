package com.dangerfield.cards.libraries.gameplay

import kotlinx.serialization.Serializable

const val SCHEMA_VERSION: Int = 1

@Serializable
data class RoomSettings(
    val smallBlind: Long,
    val bigBlind: Long,
    val startingStack: Long,
    val maxSeats: Int,
    val turnTimerSeconds: Int,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    init {
        require(smallBlind > 0) { "smallBlind must be positive" }
        require(bigBlind >= smallBlind) { "bigBlind must be ≥ smallBlind" }
        require(startingStack >= bigBlind * 10) {
            "startingStack must be at least 10 big blinds"
        }
        require(maxSeats in 2..9) { "maxSeats must be in 2..9" }
        require(turnTimerSeconds in 5..120) { "turnTimerSeconds must be in 5..120" }
    }

    companion object {
        val Default: RoomSettings = RoomSettings(
            smallBlind = 5,
            bigBlind = 10,
            startingStack = 1_000,
            maxSeats = 6,
            turnTimerSeconds = 30,
        )
    }
}
