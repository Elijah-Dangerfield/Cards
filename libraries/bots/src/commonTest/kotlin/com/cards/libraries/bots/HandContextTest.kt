package com.dangerfield.cards.libraries.bots

import com.dangerfield.cards.libraries.gameplay.PlayerAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HandContextTest {

    @Test
    fun positionOfHeadsUpButton() {
        val pos = HandContext.positionOf(seatIndex = 0, buttonSeatIndex = 0, activeSeats = listOf(0, 1))
        assertEquals(TablePosition.HeadsUpButton, pos)
    }

    @Test
    fun positionOfHeadsUpBlind() {
        val pos = HandContext.positionOf(seatIndex = 1, buttonSeatIndex = 0, activeSeats = listOf(0, 1))
        assertEquals(TablePosition.HeadsUpBlind, pos)
    }

    @Test
    fun positionOfSixHandedRotates() {
        val active = listOf(0, 1, 2, 3, 4, 5)
        // button = 2. Order from button: 2 (BTN), 3 (SB), 4 (BB), 5 (early), 0 (middle), 1 (CO/late).
        assertEquals(TablePosition.Late, HandContext.positionOf(2, 2, active))
        assertEquals(TablePosition.SmallBlind, HandContext.positionOf(3, 2, active))
        assertEquals(TablePosition.BigBlind, HandContext.positionOf(4, 2, active))
        assertEquals(TablePosition.Early, HandContext.positionOf(5, 2, active))
        assertEquals(TablePosition.Late, HandContext.positionOf(1, 2, active)) // CO
    }

    @Test
    fun foldsInFrontCountsConsecutive() {
        val ctx = HandContext(
            position = TablePosition.Late,
            streetActionsBeforeSelf = listOf(
                StreetAction(1, PlayerAction.Fold),
                StreetAction(2, PlayerAction.Fold),
                StreetAction(3, PlayerAction.Fold),
            ),
            preflopAggressorSeatIndex = null,
            selfRaisedThisStreet = false,
        )
        assertEquals(3, ctx.foldsInFront)
        assertEquals(3, ctx.consecutiveFoldStreak)
        assertTrue(ctx.isUnopened)
    }

    @Test
    fun raisesInFrontDetectedAsOpened() {
        val ctx = HandContext(
            position = TablePosition.BigBlind,
            streetActionsBeforeSelf = listOf(
                StreetAction(1, PlayerAction.Fold),
                StreetAction(2, PlayerAction.Raise(totalStreetContribution = 30, raiseAmount = 30)),
            ),
            preflopAggressorSeatIndex = 2,
            selfRaisedThisStreet = false,
        )
        assertEquals(1, ctx.raisesInFront)
        assertFalse(ctx.isUnopened)
        assertEquals(1, ctx.foldsInFront)
    }

    @Test
    fun consecutiveFoldStreakStopsAtRaise() {
        val ctx = HandContext(
            position = TablePosition.Late,
            streetActionsBeforeSelf = listOf(
                StreetAction(1, PlayerAction.Fold),
                StreetAction(2, PlayerAction.Raise(totalStreetContribution = 30, raiseAmount = 30)),
                StreetAction(3, PlayerAction.Fold),
            ),
            preflopAggressorSeatIndex = 2,
            selfRaisedThisStreet = false,
        )
        // Only the trailing single fold counts as a streak; the earlier fold is interrupted by a raise.
        assertEquals(1, ctx.consecutiveFoldStreak)
    }
}
