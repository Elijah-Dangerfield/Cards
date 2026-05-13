package com.dangerfield.cards.libraries.bots

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import kotlinx.serialization.Serializable

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
}

class OpponentTracker {
    private val profiles: MutableMap<Int, OpponentProfile> = mutableMapOf()
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
                profiles.replaceAll { _, profile -> profile.copy(handsObserved = profile.handsObserved + 1) }
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
        val existing = profiles.getOrPut(seatIndex) {
            OpponentProfile(seatIndex, handsObserved = 1.coerceAtLeast(1))
        }

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

        profiles[seatIndex] = updated
    }
}
