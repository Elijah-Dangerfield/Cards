package com.dangerfield.cards.libraries.bots

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import kotlinx.serialization.Serializable
import kotlin.concurrent.Volatile

@Serializable
data class OpponentProfile(
    val seatIndex: Int,
    val handsObserved: Int = 0,
    val vpipCount: Int = 0,
    val pfrCount: Int = 0,
    val aggCount: Int = 0,
    val callCount: Int = 0,
    val shoveCount: Int = 0,
    val streetActionCount: Int = 0,
) {
    val vpip: Double get() = ratio(vpipCount, handsObserved)
    val pfr: Double get() = ratio(pfrCount, handsObserved)
    val aggressionFrequency: Double
        get() = ratio(aggCount, aggCount + callCount).coerceIn(0.0, 1.0)
    val shoveFrequency: Double get() = ratio(shoveCount, streetActionCount)

    private fun ratio(num: Int, denom: Int): Double =
        if (denom == 0) 0.0 else num.toDouble() / denom.toDouble()

    val isShoveMonster: Boolean
        get() = handsObserved >= 5 && shoveFrequency >= 0.5

    val isPassiveCaller: Boolean
        get() = handsObserved >= 5 && aggressionFrequency <= 0.25 && vpip >= 0.4

    /**
     * A player who bets/raises far more than they call — the habitual pressurer
     * whose big bets are usually air, not a hand. Distinct from [isShoveMonster]
     * (literal all-ins): this catches the serial over-bettor a bot should call
     * down lighter, not just the jammer.
     */
    val isHabitualAggressor: Boolean
        get() = handsObserved >= 5 && aggCount >= 6 && aggressionFrequency >= 0.7
}

/**
 * Rolling per-seat opponent read, fed the engine's [GameEvent] stream. Safe for
 * a single writer ([observe], driven from one coroutine) racing concurrent
 * readers ([snapshot], called from bot-decision threads): the profile map is
 * published as an immutable value behind a [Volatile] reference and replaced
 * whole on each update, so a reader never sees a half-mutated map.
 */
class OpponentTracker {
    @Volatile
    private var profiles: Map<Int, OpponentProfile> = emptyMap()
    private val voluntaryPotEntryRecorded: MutableMap<Int, MutableSet<Int>> = mutableMapOf()
    private val preflopRaiseRecorded: MutableMap<Int, MutableSet<Int>> = mutableMapOf()
    private var currentHandNumber: Int = 0
    private var currentStreet: BettingRound = BettingRound.Preflop

    fun snapshot(seatIndex: Int): OpponentProfile =
        profiles[seatIndex] ?: OpponentProfile(seatIndex)

    fun observe(event: GameEvent) {
        when (event) {
            is GameEvent.HandStarted -> {
                currentHandNumber = event.handNumber
                currentStreet = BettingRound.Preflop
                profiles = profiles.mapValues { (_, profile) ->
                    profile.copy(handsObserved = profile.handsObserved + 1)
                }
            }
            is GameEvent.StreetAdvanced -> {
                currentStreet = event.street
            }
            is GameEvent.ActionTaken -> {
                recordAction(event.seatIndex, event.action)
            }
            else -> Unit
        }
    }

    private fun recordAction(seatIndex: Int, action: PlayerAction) {
        val existing = profiles[seatIndex] ?: OpponentProfile(seatIndex, handsObserved = 1)

        var updated = existing.copy(streetActionCount = existing.streetActionCount + 1)

        val voluntary = action !is PlayerAction.Fold && action !is PlayerAction.Check
        if (voluntary && currentStreet == BettingRound.Preflop) {
            val recorded = voluntaryPotEntryRecorded.getOrPut(currentHandNumber) { mutableSetOf() }
            if (recorded.add(seatIndex)) {
                updated = updated.copy(vpipCount = updated.vpipCount + 1)
            }
        }

        val isAggressive = action is PlayerAction.Bet ||
            action is PlayerAction.Raise ||
            (action is PlayerAction.AllIn)

        if (isAggressive) {
            updated = updated.copy(aggCount = updated.aggCount + 1)
            if (currentStreet == BettingRound.Preflop) {
                val recorded = preflopRaiseRecorded.getOrPut(currentHandNumber) { mutableSetOf() }
                if (recorded.add(seatIndex)) {
                    updated = updated.copy(pfrCount = updated.pfrCount + 1)
                }
            }
        }

        if (action is PlayerAction.Call) {
            updated = updated.copy(callCount = updated.callCount + 1)
        }

        if (action is PlayerAction.AllIn) {
            updated = updated.copy(shoveCount = updated.shoveCount + 1)
        }

        profiles = profiles + (seatIndex to updated)
    }
}
