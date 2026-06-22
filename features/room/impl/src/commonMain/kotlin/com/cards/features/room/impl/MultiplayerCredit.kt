package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.gameplay.GameState

/**
 * Table-composition gate for multiplayer credit (product-spec.md §5.4).
 *
 * A multiplayer hand only earns full MP credit — the 1.0× XP multiplier,
 * MP-mode achievements, and (when it lands) league standing — when the
 * table is **majority human**: at least two humans seated AND humans at
 * least tying the bot count. Anything bot-stacked (`2H + 4B`) or solo
 * (`1H + Xb`) is routed to solo/practice credit instead.
 *
 * Without this rule two friends could fill a six-seat room with four bots
 * and farm chip wins + league XP against weaker opposition — the exploit
 * the spec's "leagues reflect competition against humans" principle (§3.4)
 * guards against. Short-handed friend games (`2H + 2B`) stay eligible.
 *
 * The rule reads straight off the seat list, so the same call drives both
 * the credit downgrade ([HandResultSummaryBuilder]) and the table-side
 * "Practice tier · bots present" label ([RemotePokerSessionFactory]).
 */
internal object MultiplayerCredit {

    fun qualifies(state: GameState): Boolean {
        val occupied = state.seats.filter { it.playerId != null }
        val humans = occupied.count { !it.isBot }
        val bots = occupied.count { it.isBot }
        return humans >= 2 && humans >= bots
    }

    /**
     * Whether the play screen should show the "Practice tier · bots present"
     * label for this MP table — true exactly when the table doesn't qualify
     * for MP credit AND a bot is actually seated, so the copy never claims
     * bots are present at an all-human (merely under-filled) table.
     */
    fun showsPracticeTierLabel(state: GameState): Boolean {
        val botsPresent = state.seats.any { it.playerId != null && it.isBot }
        return botsPresent && !qualifies(state)
    }

    /**
     * Whether this table is the **bots-only** sub-case of practice tier — at
     * most one human seated (the local player) with at least one bot. Distinct
     * from [showsPracticeTierLabel], which also covers bot-stacked tables that
     * still have human opponents (`2H + 4B`). Bots-only is the table where no
     * human's chips are on the other side, so the explainer adds that real
     * chips aren't at stake.
     */
    fun isBotsOnly(state: GameState): Boolean {
        val occupied = state.seats.filter { it.playerId != null }
        val humans = occupied.count { !it.isBot }
        val bots = occupied.count { it.isBot }
        return humans <= 1 && bots >= 1
    }
}
