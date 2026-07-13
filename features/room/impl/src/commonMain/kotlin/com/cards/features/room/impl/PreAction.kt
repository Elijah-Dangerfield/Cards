package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.gameplay.PlayerIntent

/**
 * An action a player arms while waiting for their turn (standard poker QoL). The
 * moment the turn arrives the VM resolves it against the live [LegalActions] and
 * submits the intent, so a decision already made doesn't have to wait on the
 * player's attention.
 */
enum class PreAction {
    /**
     * Check when we can, otherwise fold. Always fires on turn arrival — a bet
     * that landed while we waited just turns it into a fold.
     */
    CheckFold,

    /**
     * Check when we can. If a bet lands before our turn we can no longer check,
     * so it disarms and hands the decision back to the player rather than firing.
     */
    CheckAny,
    ;

    /**
     * The intent to auto-submit for [seatIndex] given the live [legal] actions,
     * or null when the armed action can't fire (a [CheckAny] now facing a bet) —
     * the caller disarms and lets the player act.
     */
    fun resolve(legal: LegalActions, seatIndex: Int): PlayerIntent? = when (this) {
        CheckFold ->
            if (legal.canCheck) PlayerIntent.Check(seatIndex) else PlayerIntent.Fold(seatIndex)
        CheckAny ->
            if (legal.canCheck) PlayerIntent.Check(seatIndex) else null
    }
}
