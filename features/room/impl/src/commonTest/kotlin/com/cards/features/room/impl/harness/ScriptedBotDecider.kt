package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.bots.BotDecider
import com.dangerfield.cards.libraries.bots.BotDecisionRequest
import com.dangerfield.cards.libraries.bots.BotDecisionResult
import com.dangerfield.cards.libraries.bots.BotThought
import com.dangerfield.cards.libraries.bots.RngBotDecider
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerIntent

/**
 * Deterministic bot action source for scenario tests. Enqueue intents per seat;
 * each [decide] for a seat pops that seat's next scripted intent in play order.
 * A seat with no script left falls through to [fallback] (the real
 * [RngBotDecider]), so partially-scripted scenarios still run to completion.
 *
 * ```
 * val decider = ScriptedBotDecider()
 * decider.seat(1).raisesTo(200).folds()   // bot at seat 1: raise preflop, fold later
 * ```
 *
 * Bots act in turn order, so for a 3+-seat scenario enqueue each seat's actions
 * in the order the hand will reach them. Unscripted seats play off RNG and are
 * therefore non-deterministic — script every bot (or stack the deck so their
 * action can't change the asserted outcome) when you need a stable assertion.
 */
class ScriptedBotDecider(
    private val fallback: BotDecider = RngBotDecider,
) : BotDecider {

    private val queues = mutableMapOf<Int, ArrayDeque<(GameState) -> PlayerIntent>>()

    fun seat(index: Int): SeatScript = SeatScript(index)

    inner class SeatScript(private val seat: Int) {
        fun folds(): SeatScript = enqueue { PlayerIntent.Fold(seat) }
        fun checks(): SeatScript = enqueue { PlayerIntent.Check(seat) }
        fun calls(): SeatScript = enqueue { PlayerIntent.Call(seat) }
        fun bets(amount: Long): SeatScript = enqueue { PlayerIntent.Bet(seat, amount) }
        fun raisesTo(totalThisStreet: Long): SeatScript =
            enqueue { PlayerIntent.Raise(seat, totalThisStreet) }
        fun allIn(): SeatScript = enqueue { PlayerIntent.AllIn(seat) }

        private fun enqueue(intent: (GameState) -> PlayerIntent): SeatScript {
            queues.getOrPut(seat) { ArrayDeque() }.addLast(intent)
            return this
        }
    }

    override fun decide(request: BotDecisionRequest): BotDecisionResult {
        val next = queues[request.seatIndex]?.removeFirstOrNull()
        return if (next != null) {
            BotDecisionResult(next(request.state), ScriptedThought)
        } else {
            fallback.decide(request)
        }
    }

    private companion object {
        val ScriptedThought = BotThought(
            handStrength = 0.0,
            potOdds = 0.0,
            drawProfile = null,
            opponentNote = null,
            rationale = "scripted",
        )
    }
}
