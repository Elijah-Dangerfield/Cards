package com.dangerfield.cards.libraries.gameplay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cross-product action-table coverage for [GameEngine] (testing-plan Round 3
 * §Cross-product table tests). Each test walks a `(situation, action)` cell of
 * the betting rules and asserts the engine's response — accept with the right
 * accounting, or reject. Where [GameEngineAdvancedTest] pins individual crafted
 * scenarios, this suite sweeps the legality boundaries themselves.
 */
class GameEngineActionTableTest {

    private fun standardSettings() = RoomSettings(
        smallBlind = 5,
        bigBlind = 10,
        startingStack = 1_000,
        maxSeats = 9,
        turnTimerSeconds = 30,
    )

    private fun seat(index: Int, stack: Long = 1_000) = Seat(
        index = index,
        playerId = "p$index",
        displayName = "P$index",
        stack = stack,
        seatStatus = SeatStatus.Active,
        handParticipation = HandParticipation.InHand,
    )

    private fun start(seats: List<Seat>, button: Int = 0, seed: Long = 42L): GameState =
        GameEngine.startHand(
            settings = standardSettings(),
            seats = seats,
            handNumber = 1,
            buttonSeatIndex = button,
            deck = deterministicDeck(seed),
        ).state

    private fun apply(state: GameState, intent: PlayerIntent): GameState =
        GameEngine.applyIntent(state, intent).state

    /** Drive a heads-up hand to the flop (SB completes, BB checks the option). */
    private fun headsUpToFlop(seed: Long = 42L): GameState {
        var s = start(listOf(seat(0), seat(1)), seed = seed)
        s = apply(s, PlayerIntent.Call(0))
        s = apply(s, PlayerIntent.Check(1))
        check(s.street == BettingRound.Flop)
        return s
    }

    // ===========================================================
    // Blinds posting across seat counts
    // ===========================================================

    @Test
    fun preflopBlinds_postedCorrectlyForTwoThroughNineSeats() {
        for (n in 2..9) {
            val events = GameEngine.startHand(
                settings = standardSettings(),
                seats = List(n) { seat(it) },
                handNumber = 1,
                buttonSeatIndex = 0,
                deck = deterministicDeck(n.toLong()),
            ).events
            val blinds = events.filterIsInstance<GameEvent.BlindPosted>()
            val sb = blinds.single { it.isSmall }
            val bb = blinds.single { !it.isSmall }
            assertEquals(5L, sb.amount, "n=$n small blind amount")
            assertEquals(10L, bb.amount, "n=$n big blind amount")
            if (n == 2) {
                assertEquals(0, sb.seatIndex, "heads-up: the button posts the small blind")
                assertEquals(1, bb.seatIndex)
            } else {
                assertEquals(1, sb.seatIndex, "n=$n: small blind is first seat after the button")
                assertEquals(2, bb.seatIndex, "n=$n: big blind is second after the button")
            }
        }
    }

    @Test
    fun headsUp_buttonActsFirstPreflop_nonButtonActsFirstPostflop() {
        val pre = start(listOf(seat(0), seat(1)))
        assertEquals(0, pre.actingSeatIndex, "button acts first preflop heads-up")
        val flop = headsUpToFlop()
        assertEquals(1, flop.actingSeatIndex, "non-button acts first postflop heads-up")
    }

    // ===========================================================
    // Check legality (bet == 0 vs bet > 0)
    // ===========================================================

    @Test
    fun check_isLegalWithNoOutstandingBet() {
        val flop = headsUpToFlop()
        val checked = GameEngine.applyIntent(flop, PlayerIntent.Check(flop.actingSeatIndex!!))
        assertEquals(0L, flop.currentBetThisStreet, "precondition: nobody has bet the flop")
        assertTrue(checked.events.isNotEmpty(), "the check is accepted")
    }

    @Test
    fun check_isIllegalWhenFacingABet() {
        val pre = start(listOf(seat(0), seat(1), seat(2)))
        // Preflop UTG faces the big blind — checking is not allowed.
        assertFailsWith<IllegalArgumentException> {
            GameEngine.applyIntent(pre, PlayerIntent.Check(pre.actingSeatIndex!!))
        }
    }

    // ===========================================================
    // Call (covers vs. short stack)
    // ===========================================================

    @Test
    fun call_takesExactlyTheOutstandingAmountWhenStackCovers() {
        val pre = start(listOf(seat(0), seat(1), seat(2)))
        val utg = pre.actingSeatIndex!!
        val before = pre.seatAt(utg).stack
        val after = apply(pre, PlayerIntent.Call(utg)).seatAt(utg)
        assertEquals(before - 10L, after.stack, "calls the big blind exactly")
        assertEquals(HandParticipation.InHand, after.handParticipation, "a covered call is not an all-in")
    }

    @Test
    fun call_becomesAllInWhenStackIsShortOfTheBet() {
        // 3-handed so the BB still has to act after the short call — the street
        // doesn't auto-advance, letting us read the all-in seat mid-street.
        // button=0 → UTG=seat0, SB=seat1 (the short stack), BB=seat2.
        var s = start(listOf(seat(0, stack = 1_000), seat(1, stack = 30), seat(2, stack = 1_000)))
        s = apply(s, PlayerIntent.Raise(0, totalAmountThisStreet = 100)) // UTG raises
        val shortSeat = s.actingSeatIndex!! // seat 1 (SB), only 25 behind after posting the SB
        val mid = apply(s, PlayerIntent.Call(shortSeat))
        val after = mid.seatAt(shortSeat)
        assertEquals(BettingRound.Preflop, mid.street, "BB still has to act; street has not advanced")
        assertEquals(0L, after.stack, "the short stack can only call all-in")
        assertEquals(HandParticipation.AllIn, after.handParticipation)
        assertEquals(30L, after.contributedThisStreet, "5 posted + 25 stack")
    }

    // ===========================================================
    // Bet legality (open bet, minimum, over-stack)
    // ===========================================================

    @Test
    fun bet_aboveStack_isRejected() {
        val flop = headsUpToFlop()
        assertFailsWith<IllegalArgumentException> {
            GameEngine.applyIntent(flop, PlayerIntent.Bet(flop.actingSeatIndex!!, amount = 2_000))
        }
    }

    @Test
    fun bet_belowMinimum_isRejected() {
        val flop = headsUpToFlop()
        // Minimum open bet is the big blind (10); a partial 5 is illegal.
        assertFailsWith<IllegalArgumentException> {
            GameEngine.applyIntent(flop, PlayerIntent.Bet(flop.actingSeatIndex!!, amount = 5))
        }
    }

    @Test
    fun bet_whenFacingAnOpenBet_isRejected() {
        val pre = start(listOf(seat(0), seat(1), seat(2)))
        // There's already the big blind to act against — must raise, not bet.
        assertFailsWith<IllegalArgumentException> {
            GameEngine.applyIntent(pre, PlayerIntent.Bet(pre.actingSeatIndex!!, amount = 50))
        }
    }

    // ===========================================================
    // Fold is always available to the acting seat
    // ===========================================================

    @Test
    fun fold_isLegalForTheActingSeat() {
        val pre = start(listOf(seat(0), seat(1), seat(2)))
        val utg = pre.actingSeatIndex!!
        val after = apply(pre, PlayerIntent.Fold(utg)).seatAt(utg)
        assertEquals(HandParticipation.Folded, after.handParticipation)
    }

    // ===========================================================
    // All-in raise: reopening rules
    // ===========================================================

    @Test
    fun allInRaise_overTheMinimum_reopensActionForPriorRaiser() {
        var s = start(listOf(seat(0), seat(1, stack = 100), seat(2)))
        s = apply(s, PlayerIntent.Raise(0, totalAmountThisStreet = 30)) // UTG raises (full raise of 20)
        s = apply(s, PlayerIntent.AllIn(s.actingSeatIndex!!)) // SB all-in to 100, a 70 raise > 20
        assertFalse(s.seatAt(0).hasActedThisStreet, "a full-size all-in reopens action for seat 0")
    }

    @Test
    fun allInRaise_belowTheMinimum_doesNotReopenAction() {
        var s = start(listOf(seat(0, stack = 100), seat(1, stack = 100)))
        s = apply(s, PlayerIntent.Raise(0, totalAmountThisStreet = 60)) // full raise of 50
        s = apply(s, PlayerIntent.AllIn(1)) // all-in to 100, only a 40 raise < 50
        assertTrue(
            s.seatAt(0).hasActedThisStreet,
            "an undersized all-in does not reopen a full re-raise for seat 0",
        )
    }
}
