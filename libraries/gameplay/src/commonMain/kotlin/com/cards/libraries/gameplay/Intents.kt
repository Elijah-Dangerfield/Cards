package com.dangerfield.cards.libraries.gameplay

import kotlinx.serialization.Serializable

@Serializable
sealed class PlayerIntent {
    abstract val seatIndex: Int
    abstract val clientNonce: String?

    @Serializable
    data class Fold(
        override val seatIndex: Int,
        override val clientNonce: String? = null,
    ) : PlayerIntent()

    @Serializable
    data class Check(
        override val seatIndex: Int,
        override val clientNonce: String? = null,
    ) : PlayerIntent()

    @Serializable
    data class Call(
        override val seatIndex: Int,
        override val clientNonce: String? = null,
    ) : PlayerIntent()

    @Serializable
    data class Bet(
        override val seatIndex: Int,
        val amount: Long,
        override val clientNonce: String? = null,
    ) : PlayerIntent()

    @Serializable
    data class Raise(
        override val seatIndex: Int,
        val totalAmountThisStreet: Long,
        override val clientNonce: String? = null,
    ) : PlayerIntent()

    @Serializable
    data class AllIn(
        override val seatIndex: Int,
        override val clientNonce: String? = null,
    ) : PlayerIntent()
}
