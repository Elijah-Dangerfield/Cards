package com.dangerfield.cards.libraries.gameplay

import kotlinx.serialization.Serializable

/**
 * The five fixed stake tiers from [product-spec §5.3](../../../../../docs/product/product-spec.md#53-public-rooms).
 *
 * Each tier is a `(small blind, big blind, buy-in)` triple. Buy-in is
 * 100× big blind ("deep stack"), so a seated player has ~100 hands of
 * pressure before bust-and-rebuy.
 *
 * Public-room matchmaking uses these as buckets. Bot tables let the
 * player pick directly via [com.dangerfield.cards.features.home.impl.BotTableSetupDialog]
 * so the stake-tier concept is discoverable in solo play, ahead of MP.
 *
 * Calibration is a Phase-8 chip-economy modeling exercise — see
 * [product-spec.md item 12](../../../../../docs/product/product-spec.md).
 * Treat these numbers as the V1 starting point, not the final state.
 */
@Serializable
enum class StakeTier(
    val displayName: String,
    val smallBlind: Long,
    val bigBlind: Long,
    val buyIn: Long,
) {
    Practice(displayName = "Practice", smallBlind = 1, bigBlind = 2, buyIn = 200),
    Casual(displayName = "Casual", smallBlind = 5, bigBlind = 10, buyIn = 1_000),
    Standard(displayName = "Standard", smallBlind = 25, bigBlind = 50, buyIn = 5_000),
    High(displayName = "High", smallBlind = 100, bigBlind = 200, buyIn = 20_000),
    Premium(displayName = "Premium", smallBlind = 500, bigBlind = 1_000, buyIn = 100_000);

    fun toRoomSettings(
        maxSeats: Int = RoomSettings.Default.maxSeats,
        turnTimerSeconds: Int = RoomSettings.Default.turnTimerSeconds,
    ): RoomSettings = RoomSettings(
        smallBlind = smallBlind,
        bigBlind = bigBlind,
        startingStack = buyIn,
        maxSeats = maxSeats,
        turnTimerSeconds = turnTimerSeconds,
    )

    companion object {
        val Default: StakeTier = Casual

        fun fromName(name: String?): StakeTier =
            entries.firstOrNull { it.name == name } ?: Default
    }
}
