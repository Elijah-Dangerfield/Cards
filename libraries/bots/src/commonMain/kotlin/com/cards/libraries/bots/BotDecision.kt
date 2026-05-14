package com.dangerfield.cards.libraries.bots

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.Seat
import kotlinx.serialization.Serializable
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.random.Random

@Serializable
data class BotThought(
    val handStrength: Double,
    val potOdds: Double,
    val drawProfile: DrawProfile?,
    val opponentNote: String?,
    val rationale: String,
)

data class BotDecisionResult(
    val intent: PlayerIntent,
    val thought: BotThought,
)

object BotDecision {

    fun choose(
        state: GameState,
        seatIndex: Int,
        personality: BotPersonality,
        difficulty: BotDifficulty,
        opponentTracker: OpponentTracker = OpponentTracker(),
        random: Random = Random.Default,
        equityIterations: Int = 300,
        handContext: HandContext = HandContext.Empty,
    ): BotDecisionResult {
        val seat = state.seatAt(seatIndex)
        require(seat.canAct) { "Seat $seatIndex cannot act" }
        require(seat.holeCards.size == 2) { "Bot has no hole cards" }

        val activeOpponents = state.activeContenders.count { it.index != seatIndex }
        val toCall = (state.currentBetThisStreet - seat.contributedThisStreet).coerceAtLeast(0)
        val pot = state.pots.sumOf { it.amount } +
            state.seats.sumOf { it.contributedThisStreet }
        val potOdds = if (toCall > 0) toCall.toDouble() / (pot + toCall).toDouble() else 0.0

        val strength = if (state.street == BettingRound.Preflop) {
            HandStrength.normalizedPreflopStrength(seat.holeCards)
        } else {
            HandStrength.equityVsRandom(
                holeCards = seat.holeCards,
                community = state.community,
                numOpponents = activeOpponents.coerceAtLeast(1),
                iterations = equityIterations,
                random = random,
            )
        }

        val draws = if (state.street == BettingRound.Flop || state.street == BettingRound.Turn) {
            HandStrength.drawPotential(seat.holeCards, state.community)
        } else null

        val opponentAdjustment = computeOpponentAdjustment(state, opponentTracker, seatIndex, difficulty)
        val effectiveStrength = (strength + opponentAdjustment.strengthBias).coerceIn(0.0, 1.0)

        val tactical = computeTacticalAdjustment(
            state = state,
            seatIndex = seatIndex,
            personality = personality,
            difficulty = difficulty,
            handContext = handContext,
            toCall = toCall,
        )

        val baseFoldThreshold = computeFoldThreshold(personality, difficulty)
        val baseRaiseThreshold = computeRaiseThreshold(personality, difficulty)
        val foldThreshold = (baseFoldThreshold + tactical.foldThresholdDelta).coerceIn(0.04, 0.85)
        val raiseThreshold = (baseRaiseThreshold + tactical.raiseThresholdDelta).coerceIn(0.20, 0.95)

        val semiBluffChance = computeSemiBluffChance(personality, difficulty, draws)
        val baseBluffChance = computeBluffChance(personality, difficulty, state)
        val bluffChance = (baseBluffChance + tactical.bluffChanceDelta).coerceIn(0.0, 0.6)

        val noBetOpen = toCall == 0L
        val willBluff = random.nextDouble() < bluffChance && noBetOpen
        val willSemiBluff = draws?.hasDraw == true && random.nextDouble() < semiBluffChance

        val rationaleParts = mutableListOf<String>()
        rationaleParts += "strength=" + fmt2(effectiveStrength)
        if (opponentAdjustment.strengthBias != 0.0) {
            val sign = if (opponentAdjustment.strengthBias >= 0) "+" else ""
            rationaleParts += "opp-adj=" + sign + fmt2(opponentAdjustment.strengthBias)
        }
        if (tactical.tag != null) rationaleParts += tactical.tag
        if (toCall > 0) rationaleParts += "potOdds=" + fmt2(potOdds)
        if (draws?.hasDraw == true) rationaleParts += drawTag(draws)

        // Humanlike noise: occasionally a strong hand slow-plays as a call/check,
        // and occasionally a marginal hand just calls instead of folding.
        val slowPlayChance = if (state.street == BettingRound.Preflop) 0.0
        else (0.12 - personality.aggression * 0.10).coerceIn(0.0, 0.15)
        val willSlowPlay = random.nextDouble() < slowPlayChance
        val curiosityCall = 0.10 + (1.0 - personality.tightness) * 0.10
        val feelingCurious = random.nextDouble() < curiosityCall

        val intent = when {
            (effectiveStrength >= raiseThreshold || willBluff || willSemiBluff) && !willSlowPlay -> {
                rationaleParts += when {
                    willBluff -> "bluff"
                    willSemiBluff -> "semi-bluff"
                    else -> "value"
                }
                buildRaiseOrBet(state, seat, personality, effectiveStrength, random, toCall)
            }
            noBetOpen -> {
                rationaleParts += if (willSlowPlay) "slow-play check" else "check"
                PlayerIntent.Check(seatIndex)
            }
            // Loose threshold for calling: includes pot odds AND a curiosity factor
            // so bots don't fold robotically every marginal spot.
            potOdds <= effectiveStrength + 0.10 || feelingCurious -> {
                rationaleParts += if (feelingCurious) "curious call" else "call"
                if (toCall >= seat.stack) PlayerIntent.AllIn(seatIndex)
                else PlayerIntent.Call(seatIndex)
            }
            effectiveStrength < foldThreshold -> {
                rationaleParts += "fold"
                PlayerIntent.Fold(seatIndex)
            }
            else -> {
                rationaleParts += "marginal fold"
                PlayerIntent.Fold(seatIndex)
            }
        }

        val thought = BotThought(
            handStrength = effectiveStrength,
            potOdds = potOdds,
            drawProfile = draws,
            opponentNote = opponentAdjustment.note,
            rationale = rationaleParts.joinToString(" · "),
        )
        return BotDecisionResult(intent, thought)
    }

    private fun computeTacticalAdjustment(
        state: GameState,
        seatIndex: Int,
        personality: BotPersonality,
        difficulty: BotDifficulty,
        handContext: HandContext,
        toCall: Long,
    ): TacticalAdjustment {
        if (difficulty == BotDifficulty.Casual) return TacticalAdjustment.None

        // 1. Position modifier — applied regardless of situation.
        var foldDelta = 0.0
        var raiseDelta = 0.0
        var bluffDelta = 0.0
        val tags = mutableListOf<String>()

        when (handContext.position) {
            TablePosition.Early -> {
                foldDelta += 0.05
                raiseDelta += 0.04
            }
            TablePosition.Middle -> Unit
            TablePosition.Late, TablePosition.HeadsUpButton -> {
                foldDelta -= 0.05
                raiseDelta -= 0.05
            }
            TablePosition.SmallBlind -> {
                foldDelta += 0.02
                raiseDelta += 0.02
            }
            TablePosition.BigBlind, TablePosition.HeadsUpBlind -> {
                // BB defends wider when facing a single late open.
                if (state.street == BettingRound.Preflop && handContext.raisesInFront == 1) {
                    foldDelta -= 0.04
                }
            }
        }

        // 2. Situational modifiers (preflop).
        if (state.street == BettingRound.Preflop) {
            val late = handContext.position == TablePosition.Late ||
                handContext.position == TablePosition.HeadsUpButton ||
                handContext.position == TablePosition.SmallBlind
            val stealSpot = late && handContext.isUnopened && handContext.foldsInFront >= 1
            if (stealSpot) {
                // Steal incentive scales with aggression; the table-tightness streak
                // (consecutive folds in front) amplifies it.
                val streakBoost = handContext.consecutiveFoldStreak.coerceAtMost(4) * 0.02
                val incentive = 0.06 + personality.aggression * 0.10 + streakBoost
                raiseDelta -= incentive
                foldDelta -= 0.04
                bluffDelta += 0.04 + personality.aggression * 0.06
                tags += "steal"
            }

            if (handContext.selfRaisedThisStreet && handContext.raisesInFront > 0 && toCall > 0) {
                // We opened, got 3bet (or worse) — tighten hard unless we're a maniac.
                val tightenAmount = 0.10 - personality.aggression * 0.06
                foldDelta += tightenAmount
                raiseDelta += 0.08 - personality.aggression * 0.05
                tags += "facing-3bet"
            } else if (!handContext.selfRaisedThisStreet && handContext.raisesInFront > 0) {
                if (handContext.callersInFront >= 1) {
                    // Squeeze opportunity: open + caller in front of us.
                    raiseDelta -= 0.04 * personality.aggression
                    foldDelta += 0.02
                    tags += "squeeze"
                } else {
                    // Plain cold-call vs a raise — tighten.
                    foldDelta += 0.06
                    raiseDelta += 0.04
                    tags += "cold-call"
                }
            }
        } else {
            // 3. Postflop situational modifiers.
            val wasAggressor = handContext.preflopAggressorSeatIndex == seatIndex
            val allChecksSoFar = handContext.streetActionsBeforeSelf.isNotEmpty() &&
                handContext.streetActionsBeforeSelf.all { it.action is PlayerAction.Check }

            if (wasAggressor && toCall == 0L && (allChecksSoFar || handContext.streetActionsBeforeSelf.isEmpty())) {
                // C-bet opportunity. Continuation frequency scales with aggression.
                val cbetPush = 0.08 + personality.aggression * 0.12
                raiseDelta -= cbetPush
                bluffDelta += 0.05 + personality.aggression * 0.05
                tags += "c-bet"
            }

            val donkLeadFaced = wasAggressor && toCall > 0L &&
                handContext.streetActionsBeforeSelf.any {
                    it.action is PlayerAction.Bet || it.action is PlayerAction.Raise
                }
            if (donkLeadFaced) {
                // OOP opponent led into the raiser — usually strength; tighten.
                foldDelta += 0.05 - personality.aggression * 0.03
                tags += "donk-faced"
            }
        }

        return TacticalAdjustment(
            foldThresholdDelta = foldDelta,
            raiseThresholdDelta = raiseDelta,
            bluffChanceDelta = bluffDelta,
            tag = if (tags.isEmpty()) null else tags.joinToString(",", prefix = "ctx:"),
        )
    }

    private fun computeOpponentAdjustment(
        state: GameState,
        tracker: OpponentTracker,
        selfIndex: Int,
        difficulty: BotDifficulty,
    ): OpponentAdjustment {
        if (difficulty == BotDifficulty.Casual) return OpponentAdjustment(0.0, null)
        val opponents = state.activeContenders.filter { it.index != selfIndex }
        if (opponents.isEmpty()) return OpponentAdjustment(0.0, null)

        val shoveMonsters = opponents.count { tracker.snapshot(it.index).isShoveMonster }
        val passiveCallers = opponents.count { tracker.snapshot(it.index).isPassiveCaller }
        var bias = 0.0
        val notes = mutableListOf<String>()

        if (shoveMonsters > 0) {
            bias += 0.10
            notes += "shove-monster"
        }
        if (passiveCallers > 0) {
            bias -= 0.04
            notes += "passive-caller"
        }
        return OpponentAdjustment(bias, notes.joinToString(",").ifEmpty { null })
    }

    private fun computeFoldThreshold(personality: BotPersonality, difficulty: BotDifficulty): Double {
        // Lowered from previous values: with the curiosity-call branch in place,
        // bots fold less mechanically. Real players call wider than equity says.
        val base = 0.10 + personality.tightness * 0.26
        return when (difficulty) {
            BotDifficulty.Casual -> base + 0.05
            BotDifficulty.Standard -> base
            BotDifficulty.Challenging -> base - 0.04
        }.coerceIn(0.04, 0.75)
    }

    private fun computeRaiseThreshold(personality: BotPersonality, difficulty: BotDifficulty): Double {
        val base = 0.62 - personality.aggression * 0.22
        return when (difficulty) {
            BotDifficulty.Casual -> base + 0.08
            BotDifficulty.Standard -> base
            BotDifficulty.Challenging -> base - 0.05
        }.coerceIn(0.25, 0.95)
    }

    private fun computeBluffChance(
        personality: BotPersonality,
        difficulty: BotDifficulty,
        state: GameState,
    ): Double {
        if (difficulty == BotDifficulty.Casual) return 0.0
        val streetMultiplier = when (state.street) {
            BettingRound.Preflop -> 0.0
            BettingRound.Flop -> 0.4
            BettingRound.Turn -> 0.7
            BettingRound.River -> 1.0
            else -> 0.0
        }
        return (personality.bluffRate * streetMultiplier).coerceIn(0.0, 0.25)
    }

    private fun computeSemiBluffChance(
        personality: BotPersonality,
        difficulty: BotDifficulty,
        draws: DrawProfile?,
    ): Double {
        if (difficulty == BotDifficulty.Casual) return 0.0
        if (draws == null || !draws.hasDraw) return 0.0
        val drawWeight = when {
            draws.flushDraw && draws.openEndedStraight -> 0.55
            draws.flushDraw -> 0.40
            draws.openEndedStraight -> 0.35
            draws.gutshot -> 0.12
            else -> 0.0
        }
        return (drawWeight * (0.4 + personality.aggression * 0.6)).coerceIn(0.0, 0.55)
    }

    private fun buildRaiseOrBet(
        state: GameState,
        seat: Seat,
        personality: BotPersonality,
        strength: Double,
        random: Random,
        toCall: Long,
    ): PlayerIntent {
        val pot = state.pots.sumOf { it.amount } +
            state.seats.sumOf { it.contributedThisStreet }
        val basePotFraction = (0.5 + strength * 0.6 + personality.aggression * 0.3)
            .coerceIn(0.4, 1.5)
        val noise = (random.nextDouble() - 0.5) * 0.15
        val targetBetSize = (pot * (basePotFraction + noise)).coerceAtLeast(state.settings.bigBlind.toDouble())
        val noBetOpen = state.currentBetThisStreet == 0L
        val seatIndex = seat.index

        if (noBetOpen) {
            val amount = roundToBlindUnit(
                rawAmount = targetBetSize.roundToLong(),
                bigBlind = state.settings.bigBlind,
            ).coerceAtMost(seat.stack)
            return if (amount >= seat.stack) PlayerIntent.AllIn(seatIndex)
            else PlayerIntent.Bet(seatIndex, amount.coerceAtLeast(state.settings.bigBlind))
        }

        val minimumRaiseTotal = state.currentBetThisStreet + state.lastFullRaiseSize
        val target = (state.currentBetThisStreet + targetBetSize).roundToLong()
        val totalBet = roundToBlindUnit(
            rawAmount = maxOf(target, minimumRaiseTotal),
            bigBlind = state.settings.bigBlind,
        )
        val toContribute = (totalBet - seat.contributedThisStreet).coerceAtLeast(0)
        return if (toContribute >= seat.stack) PlayerIntent.AllIn(seatIndex)
        else PlayerIntent.Raise(seatIndex, min(totalBet, seat.contributedThisStreet + seat.stack))
    }

    private fun roundToBlindUnit(rawAmount: Long, bigBlind: Long): Long {
        if (bigBlind <= 0) return rawAmount
        val unit = bigBlind
        return ((rawAmount + unit / 2) / unit) * unit
    }

    private fun drawTag(draws: DrawProfile): String = buildString {
        if (draws.flushDraw) append("flush-draw ")
        if (draws.openEndedStraight) append("OE-straight ")
        if (draws.gutshot) append("gutshot ")
    }.trim()
}

private data class OpponentAdjustment(val strengthBias: Double, val note: String?)

private data class TacticalAdjustment(
    val foldThresholdDelta: Double,
    val raiseThresholdDelta: Double,
    val bluffChanceDelta: Double,
    val tag: String?,
) {
    companion object {
        val None: TacticalAdjustment = TacticalAdjustment(0.0, 0.0, 0.0, null)
    }
}

private fun fmt2(value: Double): String {
    val scaled = kotlin.math.round(value * 100.0).toLong()
    val sign = if (scaled < 0) "-" else ""
    val abs = kotlin.math.abs(scaled)
    val whole = abs / 100
    val frac = abs % 100
    val fracStr = if (frac < 10) "0$frac" else frac.toString()
    return "$sign$whole.$fracStr"
}
