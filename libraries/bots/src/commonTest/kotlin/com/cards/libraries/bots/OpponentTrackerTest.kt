package com.dangerfield.cards.libraries.bots

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpponentTrackerTest {

    private fun handStart(handNumber: Int = 1, buttonIndex: Int = 0): GameEvent =
        GameEvent.HandStarted(sequence = 1, handNumber = handNumber, buttonSeatIndex = buttonIndex)

    private fun streetAdvanced(street: BettingRound): GameEvent =
        GameEvent.StreetAdvanced(sequence = 2, street = street, communityCards = emptyList())

    private fun action(seatIndex: Int, action: PlayerAction): GameEvent =
        GameEvent.ActionTaken(sequence = 3, seatIndex = seatIndex, action = action, resultingStreetContribution = 0)

    @Test
    fun emptyProfileForUnseenSeat() {
        val tracker = OpponentTracker()
        val profile = tracker.snapshot(0)
        assertEquals(0, profile.handsObserved)
        assertEquals(0.0, profile.vpip)
    }

    @Test
    fun shoveMonsterDetectedAfterFiveShoves() {
        val tracker = OpponentTracker()
        repeat(6) { hand ->
            tracker.observe(handStart(handNumber = hand + 1))
            tracker.observe(action(1, PlayerAction.AllIn(100)))
        }
        val profile = tracker.snapshot(1)
        assertTrue(profile.isShoveMonster, "expected shove-monster, got $profile")
        assertTrue(profile.shoveFrequency >= 0.5)
    }

    @Test
    fun vpipCountsOncePerHand() {
        val tracker = OpponentTracker()
        tracker.observe(handStart(handNumber = 1))
        tracker.observe(action(1, PlayerAction.Call(10)))
        tracker.observe(action(1, PlayerAction.Call(20)))
        tracker.observe(action(1, PlayerAction.Call(30)))
        val profile = tracker.snapshot(1)
        assertEquals(1, profile.vpipCount)
    }

    @Test
    fun pfrOnlyOnPreflop() {
        val tracker = OpponentTracker()
        tracker.observe(handStart(handNumber = 1))
        tracker.observe(streetAdvanced(BettingRound.Preflop))
        tracker.observe(action(1, PlayerAction.Raise(20, 10)))
        tracker.observe(streetAdvanced(BettingRound.Flop))
        tracker.observe(action(1, PlayerAction.Raise(40, 30)))
        val profile = tracker.snapshot(1)
        assertEquals(1, profile.pfrCount)
        assertEquals(2, profile.aggCount)
    }

    @Test
    fun passiveCallerDetected() {
        val tracker = OpponentTracker()
        repeat(6) { hand ->
            tracker.observe(handStart(handNumber = hand + 1))
            tracker.observe(action(1, PlayerAction.Call(10)))
            tracker.observe(action(1, PlayerAction.Call(20)))
        }
        val profile = tracker.snapshot(1)
        assertTrue(profile.isPassiveCaller, "expected passive caller, got $profile")
    }

    @Test
    fun handsObservedIncrementsOncePerStart() {
        val tracker = OpponentTracker()
        tracker.observe(handStart(handNumber = 1))
        tracker.observe(action(1, PlayerAction.Call(10)))
        tracker.observe(handStart(handNumber = 2))
        tracker.observe(action(1, PlayerAction.Fold))
        val profile = tracker.snapshot(1)
        assertEquals(2, profile.handsObserved)
    }

    @Test
    fun habitualAggressorDetected_butNotFlaggedAsShoveMonster() {
        val tracker = OpponentTracker()
        repeat(6) { hand ->
            tracker.observe(handStart(handNumber = hand + 1))
            tracker.observe(action(1, PlayerAction.Raise(40, 20)))
        }
        val profile = tracker.snapshot(1)
        assertTrue(profile.isHabitualAggressor, "expected habitual aggressor, got $profile")
        assertFalse(profile.isShoveMonster, "a raiser who never jams is not a shove monster")
    }

    @Test
    fun occasionalRaiserIsNotHabitualAggressor() {
        val tracker = OpponentTracker()
        repeat(6) { hand ->
            tracker.observe(handStart(handNumber = hand + 1))
            tracker.observe(action(1, PlayerAction.Call(10)))
        }
        // One lone raise among six calls — not a pattern.
        tracker.observe(action(1, PlayerAction.Raise(40, 20)))
        assertFalse(tracker.snapshot(1).isHabitualAggressor)
    }

    @Test
    fun checkActionIsNotVoluntary() {
        val tracker = OpponentTracker()
        tracker.observe(handStart(handNumber = 1))
        tracker.observe(action(1, PlayerAction.Check))
        val profile = tracker.snapshot(1)
        assertEquals(0, profile.vpipCount)
    }

    @Test
    fun foldDoesNotCountAsVoluntary() {
        val tracker = OpponentTracker()
        tracker.observe(handStart(handNumber = 1))
        tracker.observe(action(1, PlayerAction.Fold))
        val profile = tracker.snapshot(1)
        assertEquals(0, profile.vpipCount)
        assertFalse(profile.isShoveMonster)
    }
}
