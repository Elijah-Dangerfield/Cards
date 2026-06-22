package com.dangerfield.cards.libraries.bots

import com.dangerfield.cards.libraries.gameplay.GameState
import kotlin.random.Random

/**
 * Pluggable source of bot actions. Production uses [RngBotDecider], which is a
 * thin adapter over the existing [BotDecision.choose] algorithm — so swapping
 * the call site behind this interface changes nothing about how bots play.
 *
 * Tests inject a scripted decider (e.g. `ScriptedBotDecider`) to force a bot to
 * take a specific action ("raise to 200") and then assert the resulting UI,
 * which the RNG-driven [BotDecision.choose] makes impossible.
 */
fun interface BotDecider {
    fun decide(request: BotDecisionRequest): BotDecisionResult
}

/**
 * Everything [BotDecision.choose] needs, bundled so [BotDecider] can stay a
 * single-method interface. Mirrors the parameters of [BotDecision.choose]
 * one-for-one; defaults match that function's defaults.
 */
data class BotDecisionRequest(
    val state: GameState,
    val seatIndex: Int,
    val personality: BotPersonality,
    val difficulty: BotDifficulty,
    val opponentTracker: OpponentTracker = OpponentTracker(),
    val random: Random = Random.Default,
    val equityIterations: Int = 300,
    val handContext: HandContext = HandContext.Empty,
)

/**
 * Production decider — delegates verbatim to [BotDecision.choose]. Kept as a
 * thin adapter (rather than moving the algorithm) so the existing
 * `BotDecision*Test` suites stay authoritative and production behaviour is
 * provably identical to before the seam existed.
 */
object RngBotDecider : BotDecider {
    override fun decide(request: BotDecisionRequest): BotDecisionResult =
        BotDecision.choose(
            state = request.state,
            seatIndex = request.seatIndex,
            personality = request.personality,
            difficulty = request.difficulty,
            opponentTracker = request.opponentTracker,
            random = request.random,
            equityIterations = request.equityIterations,
            handContext = request.handContext,
        )
}
