package com.dangerfield.cards.libraries.gameplay

import kotlinx.serialization.Serializable

@Serializable
enum class SeatStatus {
    Active,
    SittingOut,
    Empty,
}

@Serializable
enum class HandParticipation {
    InHand,
    Folded,
    AllIn,
    NotDealt,
}

@Serializable
data class Seat(
    val index: Int,
    val playerId: String?,
    val displayName: String,
    val stack: Long,
    val seatStatus: SeatStatus,
    val handParticipation: HandParticipation,
    val isBot: Boolean = false,
    val contributedThisStreet: Long = 0,
    val contributedThisHand: Long = 0,
    val holeCards: List<Card> = emptyList(),
    val hasActedThisStreet: Boolean = false,
    /**
     * Product ids of the cosmetics this player shows off at the table — their
     * equipped Badge-/Title-slot grants. Public (no scrub needed); the client
     * resolves each id to its display metadata from the catalog so an opponent's
     * player card can render their badges. Empty for bots and pre-resolve seats.
     */
    val badgeProductIds: List<String> = emptyList(),
) {
    val isInHand: Boolean
        get() = handParticipation == HandParticipation.InHand ||
            handParticipation == HandParticipation.AllIn

    val canAct: Boolean
        get() = handParticipation == HandParticipation.InHand && stack > 0

    val isEmpty: Boolean
        get() = playerId == null
}
