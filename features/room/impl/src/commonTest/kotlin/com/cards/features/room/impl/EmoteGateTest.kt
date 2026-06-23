package com.dangerfield.cards.features.room.impl

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exhaustive tests for [EmoteGate] — the emote cooldown + remote-render gating
 * extracted from [PlayPokerViewModel]. SeatViews are produced via the real
 * [TableUiState.fromGameState] projection so the [seatMuteKey] derivation is
 * exercised end-to-end.
 */
class EmoteGateTest {

    private val table = TableUiState.fromGameState(
        gameState = stubGameState(), // seat 0 = human "You", seat 1 = bot "Steve"
        humanSeatIndex = 0,
        personalitiesBySeat = emptyMap(),
        lastWinners = null,
        lastActionBySeat = emptyMap(),
    )
    private val humanSeat = table.seats.first { it.isHuman }
    private val opponentSeat = table.seats.first { !it.isHuman }

    // ---------- canBlast (cooldown boundary) ----------

    @Test
    fun canBlast_falseWhileCoolingDown() {
        assertFalse(EmoteGate.canBlast(nowMs = 1_000, cooldownEndsAtMs = 5_000))
    }

    @Test
    fun canBlast_trueExactlyAtTheDeadline() {
        assertTrue(EmoteGate.canBlast(nowMs = 5_000, cooldownEndsAtMs = 5_000))
    }

    @Test
    fun canBlast_trueAfterTheDeadline_andWhenNoCooldown() {
        assertTrue(EmoteGate.canBlast(nowMs = 5_001, cooldownEndsAtMs = 5_000))
        assertTrue(EmoteGate.canBlast(nowMs = 0, cooldownEndsAtMs = 0))
    }

    // ---------- shouldRenderRemote ----------

    @Test
    fun shouldRenderRemote_falseForUnknownSeat() {
        assertFalse(EmoteGate.shouldRenderRemote(seat = null, mutedKeys = emptySet()))
    }

    @Test
    fun shouldRenderRemote_falseForOwnEcho() {
        assertFalse(EmoteGate.shouldRenderRemote(humanSeat, mutedKeys = emptySet()))
    }

    @Test
    fun shouldRenderRemote_falseForMutedSeat() {
        val muted = setOfNotNull(seatMuteKey(opponentSeat))
        assertFalse(EmoteGate.shouldRenderRemote(opponentSeat, mutedKeys = muted))
    }

    @Test
    fun shouldRenderRemote_trueForUnmutedOpponent() {
        assertTrue(EmoteGate.shouldRenderRemote(opponentSeat, mutedKeys = emptySet()))
    }
}
