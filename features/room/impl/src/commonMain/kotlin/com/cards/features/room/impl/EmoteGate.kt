package com.dangerfield.cards.features.room.impl

/**
 * Pure gating for table emotes, split out of [PlayPokerViewModel] for direct
 * unit testing. The VM keeps the state mutation, wire send, and clock read;
 * this object owns the two yes/no decisions.
 */
object EmoteGate {

    /** True when a fresh outbound blast is allowed — the cooldown has elapsed. */
    fun canBlast(nowMs: Long, cooldownEndsAtMs: Long): Boolean = nowMs >= cooldownEndsAtMs

    /**
     * Whether an inbound remote emote for [seat] should render. Drops:
     *  - an unknown seat (`null` — index not in the table),
     *  - the local human's own echo (already rendered on tap),
     *  - any seat the user has muted ([seatMuteKey] present in [mutedKeys]).
     */
    fun shouldRenderRemote(seat: SeatView?, mutedKeys: Set<String>): Boolean {
        if (seat == null || seat.isHuman) return false
        return seatMuteKey(seat) !in mutedKeys
    }
}
