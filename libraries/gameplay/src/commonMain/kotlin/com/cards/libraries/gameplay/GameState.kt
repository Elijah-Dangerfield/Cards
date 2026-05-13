package com.dangerfield.cards.libraries.gameplay

import kotlinx.serialization.Serializable

@Serializable
data class GameState(
    val schemaVersion: Int = SCHEMA_VERSION,
    val settings: RoomSettings,
    val handNumber: Int,
    val buttonSeatIndex: Int,
    val seats: List<Seat>,
    val community: List<Card>,
    val street: BettingRound,
    val currentBetThisStreet: Long,
    val lastFullRaiseSize: Long,
    val actingSeatIndex: Int?,
    val deckRemaining: List<Card>,
    val pots: List<Pot> = emptyList(),
    val lastSequence: Long = 0,
) {
    val activeContenders: List<Seat>
        get() = seats.filter { it.isInHand }

    val playersAbleToAct: List<Seat>
        get() = seats.filter { it.canAct }

    fun seatAt(index: Int): Seat = seats.first { it.index == index }

    fun nextActiveSeatAfter(index: Int): Seat? {
        if (seats.isEmpty()) return null
        val n = seats.size
        var i = (index + 1) % n
        while (i != index) {
            val seat = seats[i]
            if (seat.canAct) return seat
            i = (i + 1) % n
        }
        return null
    }
}

@Serializable
data class Pot(
    val amount: Long,
    val eligibleSeatIndexes: List<Int>,
)
