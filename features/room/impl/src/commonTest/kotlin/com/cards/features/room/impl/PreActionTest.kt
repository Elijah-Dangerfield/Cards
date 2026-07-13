package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The money-path core of the pre-action feature (GAME-30): resolving an armed
 * toggle against the live legal actions the moment the turn lands. The scenario
 * suite drives the VM end-to-end; this pins the branch table in isolation.
 */
class PreActionTest {

    private fun legal(canCheck: Boolean): LegalActions = LegalActions(
        canCheck = canCheck,
        canCall = !canCheck,
        callAmount = if (canCheck) 0 else 20,
        canRaise = true,
        isOpenBet = canCheck,
        minRaiseTotal = 20,
        maxRaiseTotal = 200,
        canAllIn = true,
        allInAmount = 200,
        potIfYouCall = 40,
    )

    @Test
    fun checkFold_checksWhenCheckingIsLegal() {
        assertEquals(
            PlayerIntent.Check(seatIndex = 0),
            PreAction.CheckFold.resolve(legal(canCheck = true), seatIndex = 0),
        )
    }

    @Test
    fun checkFold_foldsWhenFacingABet() {
        assertEquals(
            PlayerIntent.Fold(seatIndex = 2),
            PreAction.CheckFold.resolve(legal(canCheck = false), seatIndex = 2),
        )
    }

    @Test
    fun checkAny_checksWhenCheckingIsLegal() {
        assertEquals(
            PlayerIntent.Check(seatIndex = 3),
            PreAction.CheckAny.resolve(legal(canCheck = true), seatIndex = 3),
        )
    }

    @Test
    fun checkAny_disarmsRatherThanFiringWhenFacingABet() {
        assertNull(PreAction.CheckAny.resolve(legal(canCheck = false), seatIndex = 0))
    }
}
