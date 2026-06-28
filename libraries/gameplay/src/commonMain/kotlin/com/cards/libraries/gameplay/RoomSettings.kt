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
        /** Smallest buy-in a host may pick — keeps blinds valid (≥ 2/1). */
        const val MIN_BUY_IN: Long = 100

        /** Sanity ceiling so a tampered request can't create an absurd table. */
        const val MAX_BUY_IN: Long = 1_000_000_000

        /** Buy-in the server assumes when a create request omits one entirely. */
        const val DEFAULT_BUY_IN: Long = 5_000

        /**
         * Buy-in pre-selected on the create-table screen for a first-time host
         * (ROOM-13). Deliberately a fraction of the 10,000-chip starter grant —
         * ~10% — so a new player commits a sensible slice of their bankroll to one
         * table, keeping plenty back for rebuys and a second table, rather than the
         * old half-bankroll 5,000 default. At 100 big blinds this is the classic
         * 5/10 small-stakes feel. Distinct from [DEFAULT_BUY_IN] (the protocol
         * fallback) so the host-facing default can move without shifting the wire
         * default or the matchmaker's nearest-tier snapping.
         */
        const val DEFAULT_HOST_BUY_IN: Long = 1_000

        val Default: RoomSettings = RoomSettings(
            smallBlind = 5,
            bigBlind = 10,
            startingStack = 1_000,
            maxSeats = 6,
            turnTimerSeconds = 30,
        )

        /**
         * Derive a full table config from a single [buyIn] — the one number the
         * host picks. The buy-in IS each player's starting stack; blinds scale at
         * ~100 big blinds per stack (`bigBlind = buyIn / 100`), floored to 2/1 so
         * the smallest tables stay valid. Mirrors the "Blinds 25 / 50" caption a
         * 5,000 buy-in shows today.
         */
        fun forBuyIn(buyIn: Long, maxSeats: Int): RoomSettings {
            val stack = buyIn.coerceIn(MIN_BUY_IN, MAX_BUY_IN)
            val bigBlind = (stack / 100).coerceAtLeast(2)
            val smallBlind = (bigBlind / 2).coerceAtLeast(1)
            return RoomSettings(
                smallBlind = smallBlind,
                bigBlind = bigBlind,
                startingStack = stack,
                maxSeats = maxSeats,
                turnTimerSeconds = 30,
            )
        }
    }
}
