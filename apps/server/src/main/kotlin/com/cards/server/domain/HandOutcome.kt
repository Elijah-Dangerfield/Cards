package com.dangerfield.cards.server.domain

/**
 * Per-hand-shape signals the server witnesses the moment a hand completes,
 * carried out of [com.dangerfield.cards.server.game.GameSession] so the
 * registry can drive the per-hand server-witnessed MP achievements
 * ([ServerWitnessedAchievements.evaluateHand]).
 *
 * Only humans appear in [perHuman] — bots never carry a server-witnessed
 * grant. Keyed by Supabase user id.
 */
data class HandOutcome(
    val handNumber: Int,
    val perHuman: Map<String, PlayerHandOutcome>,
)

/**
 * One human's outcome for a single finished hand. All signals are derived
 * from the final [com.dangerfield.cards.libraries.gameplay.GameState] plus
 * the per-seat stacks captured at the start of the hand.
 *
 * - [won] — ended the hand with more chips than they started it with (took
 *   chips from the pot). A pure chop that returns exactly the contribution
 *   is not a win.
 * - [stackMultiple] — final stack ÷ start-of-hand stack. `2.0` means the
 *   player doubled up this hand; `0.0` when the start stack was unknown
 *   (e.g. a hand that began before a server restart, so the in-memory
 *   start-stack snapshot was lost).
 * - [bustsDealt] — opponents (human or bot) who finished this hand busted
 *   (dealt in with chips, now at zero), credited only when this player
 *   [won] the hand. Multiway side-pots can over-credit a winner who didn't
 *   actually take the busted player's chips; acceptable for a play-money
 *   achievement.
 * - [potTotal] — total chips contested this hand (sum of every seat's
 *   contribution). Same value for every participant.
 * - [wonByFold] — took the pot without reaching showdown (every other player
 *   folded). Derived from the hand's `HandEnded` event (`HandWinner.byFold`),
 *   not the stack delta, so an all-in showdown never counts.
 */
data class PlayerHandOutcome(
    val won: Boolean,
    val stackMultiple: Double,
    val bustsDealt: Int,
    val potTotal: Long,
    val wonByFold: Boolean = false,
)
