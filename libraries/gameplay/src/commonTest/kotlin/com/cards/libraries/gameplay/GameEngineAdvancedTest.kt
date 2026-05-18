package com.dangerfield.cards.libraries.gameplay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Advanced [GameEngine] coverage on top of [GameEngineTest]. This suite
 * focuses on the harder-to-reason-about poker rules that escape unit tests
 * but matter for correctness:
 *
 *  - **Showdown winner determination** with crafted decks so we know exactly
 *    which hand should win and which seat should get the pot.
 *  - **Split pots** when contenders tie on hand rank.
 *  - **Side pots** when stacks are unequal at all-in — main pot capped at
 *    short stack × N, side pot for the surplus, eligible-seat tracking.
 *  - **Re-raise mechanics** — when action reopens, when it doesn't, min-raise
 *    enforcement after a raise.
 *  - **All-in for less than min raise** doesn't reopen action for prior actors.
 *  - **Stack accounting** — exact chip movement on each action type.
 *  - **Sitting-out / busted seats** are skipped at hand start and in the
 *    acting order.
 *  - **Card revelation** at showdown follows the "in-hand only" rule.
 *
 * Crafted decks: [Deck.fromOrdered] takes a fixed 52-card list. The engine
 * deals two cards per seat in index order, then 3-card flop, 1-card turn,
 * 1-card river (no burn cards). [craftDeck] is a helper that lets each
 * test express "these are the hole cards I want for each seat, these are
 * the community cards", and pads with arbitrary remaining cards.
 */
class GameEngineAdvancedTest {

    // ---------- Helpers ----------

    private fun standardSettings(startingStack: Long = 1_000L) = RoomSettings(
        smallBlind = 5,
        bigBlind = 10,
        startingStack = startingStack,
        maxSeats = 6,
        turnTimerSeconds = 30,
    )

    private fun seat(
        index: Int,
        stack: Long = 1_000,
        status: SeatStatus = SeatStatus.Active,
    ) = Seat(
        index = index,
        playerId = "p$index",
        displayName = "P$index",
        stack = stack,
        seatStatus = status,
        handParticipation = HandParticipation.InHand,
    )

    /**
     * Build a 52-card deck where the first cards are the chosen hole cards
     * (2 per seat in index order) followed by the chosen community cards
     * (flop + turn + river). Remaining slots fill with the rest of the deck
     * in canonical order.
     */
    private fun craftDeck(
        seatHoleCards: List<List<Card>>,
        community: List<Card>,
    ): Deck {
        val ordered = mutableListOf<Card>()
        seatHoleCards.forEach { hole ->
            require(hole.size == 2) { "Each seat needs 2 hole cards" }
            ordered.addAll(hole)
        }
        ordered.addAll(community)
        val used = ordered.toSet()
        require(used.size == ordered.size) { "Duplicate cards in crafted scenario: $ordered" }
        val rest = Card.fullDeck.filterNot { it in used }
        ordered.addAll(rest)
        return Deck.fromOrdered(ordered)
    }

    private fun startWith(
        seats: List<Seat>,
        deck: Deck,
        settings: RoomSettings = standardSettings(),
        buttonIndex: Int = 0,
        handNumber: Int = 1,
    ): GameState = GameEngine.startHand(
        settings = settings,
        seats = seats,
        handNumber = handNumber,
        buttonSeatIndex = buttonIndex,
        deck = deck,
    ).state

    /** Apply [intent] and return the new state — convenience wrapper. */
    private fun apply(state: GameState, intent: PlayerIntent): GameState =
        GameEngine.applyIntent(state, intent).state

    // ===========================================================
    // Showdown winner determination
    // ===========================================================

    @Test
    fun showdown_higherFlushWinsAgainstPair() {
        // Heads-up. Seat 0 holds A♠ K♠; seat 1 holds 7♥ 7♣. Board is
        // 2♠ 5♠ 9♠ J♦ 3♦ — seat 0 makes a flush, seat 1 has just a pair.
        val seats = listOf(seat(0, stack = 100), seat(1, stack = 100))
        val deck = craftDeck(
            seatHoleCards = listOf(
                listOf(Card(Rank.Ace, Suit.Spades), Card(Rank.King, Suit.Spades)),
                listOf(Card(Rank.Seven, Suit.Hearts), Card(Rank.Seven, Suit.Clubs)),
            ),
            community = listOf(
                Card(Rank.Two, Suit.Spades),
                Card(Rank.Five, Suit.Spades),
                Card(Rank.Nine, Suit.Spades),
                Card(Rank.Jack, Suit.Diamonds),
                Card(Rank.Three, Suit.Diamonds),
            ),
        )
        // Heads-up: seat 0 is button + SB, seat 1 is BB. Play all-in to skip
        // betting variability — focus is on winner determination.
        var s = startWith(seats, deck)
        s = apply(s, PlayerIntent.AllIn(s.actingSeatIndex!!))
        val result = GameEngine.applyIntent(s, PlayerIntent.Call(s.actingSeatIndex!!))

        val ended = result.events.filterIsInstance<GameEvent.HandEnded>().single()
        assertEquals(1, ended.winners.size, "single winner — no split")
        assertEquals(0, ended.winners.first().seatIndex, "seat 0 with the flush wins")
        assertFalse(ended.winners.first().byFold, "showdown, not fold")
        assertEquals(HandCategory.Flush, ended.winners.first().handRank?.category)
        assertEquals(200L, ended.winners.first().amount, "winner gets both 100-stack contributions")
    }

    @Test
    fun showdown_threeOfAKindBeatsTwoPair() {
        val seats = listOf(seat(0, stack = 100), seat(1, stack = 100))
        // Seat 0: 8♠ 8♣ → trips on 8♥ flop.
        // Seat 1: A♦ K♦ → two pair (As + Ks) using board.
        val deck = craftDeck(
            seatHoleCards = listOf(
                listOf(Card(Rank.Eight, Suit.Spades), Card(Rank.Eight, Suit.Clubs)),
                listOf(Card(Rank.Ace, Suit.Diamonds), Card(Rank.King, Suit.Diamonds)),
            ),
            community = listOf(
                Card(Rank.Eight, Suit.Hearts),
                Card(Rank.Ace, Suit.Hearts),
                Card(Rank.King, Suit.Hearts),
                Card(Rank.Two, Suit.Clubs),
                Card(Rank.Three, Suit.Clubs),
            ),
        )
        var s = startWith(seats, deck)
        s = apply(s, PlayerIntent.AllIn(s.actingSeatIndex!!))
        val end = GameEngine.applyIntent(s, PlayerIntent.Call(s.actingSeatIndex!!))

        val ended = end.events.filterIsInstance<GameEvent.HandEnded>().single()
        assertEquals(0, ended.winners.single().seatIndex)
        assertEquals(HandCategory.ThreeOfAKind, ended.winners.single().handRank?.category)
    }

    @Test
    fun showdown_splitPotOnTie() {
        // Both seats hold A♠ K♠ equivalents — using suit irrelevance for the
        // straight. Hole cards picked so both make the same straight on the
        // same board. Both get exactly half the pot.
        val seats = listOf(seat(0, stack = 100), seat(1, stack = 100))
        // Both make a straight T-J-Q-K-A using the board.
        val deck = craftDeck(
            seatHoleCards = listOf(
                listOf(Card(Rank.Two, Suit.Spades), Card(Rank.Three, Suit.Spades)),
                listOf(Card(Rank.Two, Suit.Hearts), Card(Rank.Three, Suit.Hearts)),
            ),
            community = listOf(
                Card(Rank.Ten, Suit.Clubs),
                Card(Rank.Jack, Suit.Diamonds),
                Card(Rank.Queen, Suit.Clubs),
                Card(Rank.King, Suit.Diamonds),
                Card(Rank.Ace, Suit.Clubs),
            ),
        )
        var s = startWith(seats, deck)
        s = apply(s, PlayerIntent.AllIn(s.actingSeatIndex!!))
        val end = GameEngine.applyIntent(s, PlayerIntent.Call(s.actingSeatIndex!!))

        val ended = end.events.filterIsInstance<GameEvent.HandEnded>().single()
        assertEquals(2, ended.winners.size, "split between two ties")
        // Total pot = 200 chips. Even split → 100 each.
        assertEquals(setOf(100L), ended.winners.map { it.amount }.toSet())
        assertEquals(setOf(0, 1), ended.winners.map { it.seatIndex }.toSet())
    }

    @Test
    fun showdown_revealsHoleCardsForInHandSeatsOnly() {
        // 3-handed. Seat 1 folds preflop. After showdown, only seats 0 and 2
        // should have revealedHoleCards entries.
        val seats = listOf(seat(0, stack = 100), seat(1, stack = 100), seat(2, stack = 100))
        // Deck order will deal: seat 0 (2 cards), seat 1 (2 cards), seat 2 (2 cards), then board.
        val deck = craftDeck(
            seatHoleCards = listOf(
                listOf(Card(Rank.Ace, Suit.Spades), Card(Rank.King, Suit.Spades)),
                listOf(Card(Rank.Seven, Suit.Hearts), Card(Rank.Two, Suit.Diamonds)),  // seat 1 folds
                listOf(Card(Rank.Queen, Suit.Spades), Card(Rank.Jack, Suit.Spades)),
            ),
            community = listOf(
                Card(Rank.Three, Suit.Clubs),
                Card(Rank.Four, Suit.Hearts),
                Card(Rank.Six, Suit.Clubs),
                Card(Rank.Eight, Suit.Diamonds),
                Card(Rank.Ten, Suit.Spades),
            ),
        )
        var s = startWith(seats, deck)
        // 3-handed preflop order: first to act is seat 0 (UTG). Then SB (1), BB (2).
        s = apply(s, PlayerIntent.Call(s.actingSeatIndex!!))  // seat 0 calls 10
        s = apply(s, PlayerIntent.Fold(s.actingSeatIndex!!))  // seat 1 folds
        s = apply(s, PlayerIntent.Check(s.actingSeatIndex!!))  // seat 2 checks option
        // Flop / turn / river all check-check between seats 0 and 2.
        while (s.street != BettingRound.Complete) {
            s = apply(s, PlayerIntent.Check(s.actingSeatIndex!!))
        }

        val revealed = s.seats // re-check; need the events for the actual map
        // Get the actual revealed map from the HandEnded event by re-running and capturing.
        val ev = GameEngine.startHand(
            settings = standardSettings(),
            seats = seats,
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = craftDeck(
                seatHoleCards = listOf(
                    listOf(Card(Rank.Ace, Suit.Spades), Card(Rank.King, Suit.Spades)),
                    listOf(Card(Rank.Seven, Suit.Hearts), Card(Rank.Two, Suit.Diamonds)),
                    listOf(Card(Rank.Queen, Suit.Spades), Card(Rank.Jack, Suit.Spades)),
                ),
                community = listOf(
                    Card(Rank.Three, Suit.Clubs),
                    Card(Rank.Four, Suit.Hearts),
                    Card(Rank.Six, Suit.Clubs),
                    Card(Rank.Eight, Suit.Diamonds),
                    Card(Rank.Ten, Suit.Spades),
                ),
            ),
        )
        var st = ev.state
        var allEvents = ev.events
        for (intent in listOf(
            PlayerIntent.Call(st.actingSeatIndex!!),
            PlayerIntent.Fold(seatIndex = 1),  // seat 1 next
            PlayerIntent.Check(seatIndex = 2),
        )) {
            val r = GameEngine.applyIntent(st, intent)
            st = r.state
            allEvents = allEvents + r.events
        }
        while (st.street != BettingRound.Complete) {
            val r = GameEngine.applyIntent(st, PlayerIntent.Check(st.actingSeatIndex!!))
            st = r.state
            allEvents = allEvents + r.events
        }

        val ended = allEvents.filterIsInstance<GameEvent.HandEnded>().single()
        assertEquals(
            setOf(0, 2),
            ended.revealedHoleCards.keys,
            "only seats still in the hand at showdown have revealed cards",
        )
        assertEquals(2, ended.revealedHoleCards[0]?.size)
        assertEquals(2, ended.revealedHoleCards[2]?.size)
    }

    // ===========================================================
    // Side pots — short stack vs. larger stacks
    // ===========================================================

    @Test
    fun sidePot_shortStackWinsMain_bigStackGetsSide() {
        // Heads-up isn't enough for a side pot, need 3 players. Setup:
        //  - Seat 0: stack 50 (short stack, eligible only for main pot)
        //  - Seat 1: stack 200
        //  - Seat 2: stack 200 (button)
        // Everyone all-in. Seat 0 makes the best hand (a flush) — wins main pot
        // (50 × 3 = 150). Seat 1 has a higher kicker than seat 2 → wins side
        // pot (150 × 2 = 300 from seats 1 + 2's surplus over 50).
        val seats = listOf(
            seat(0, stack = 50),
            seat(1, stack = 200),
            seat(2, stack = 200),
        )
        // Seat 0: A♠ K♠ → flush on a spade-heavy board.
        // Seat 1: A♣ Q♦ → high card, A-Q-9-7-5.
        // Seat 2: A♥ J♦ → high card, A-J-9-7-5.
        // Board picked to avoid pairs and the wheel straight (no 4 → no
        // A-2-3-4-5), so seats 1 and 2 truly differ only on kicker.
        val deck = craftDeck(
            seatHoleCards = listOf(
                listOf(Card(Rank.Ace, Suit.Spades), Card(Rank.King, Suit.Spades)),
                listOf(Card(Rank.Ace, Suit.Clubs), Card(Rank.Queen, Suit.Diamonds)),
                listOf(Card(Rank.Ace, Suit.Hearts), Card(Rank.Jack, Suit.Diamonds)),
            ),
            community = listOf(
                Card(Rank.Two, Suit.Spades),
                Card(Rank.Five, Suit.Spades),
                Card(Rank.Nine, Suit.Spades),
                Card(Rank.Three, Suit.Clubs),
                Card(Rank.Seven, Suit.Diamonds),
            ),
        )
        // Button = seat 2 — gives reasonable acting order.
        var s = startWith(seats, deck, buttonIndex = 2)
        // Push everyone all-in.
        while (s.actingSeatIndex != null) {
            s = apply(s, PlayerIntent.AllIn(s.actingSeatIndex!!))
        }

        assertEquals(BettingRound.Complete, s.street)
        // Two pots: main (eligible 0, 1, 2) + side (eligible 1, 2).
        assertEquals(2, s.pots.size, "main pot + side pot")
        val main = s.pots[0]
        val side = s.pots[1]
        assertEquals(setOf(0, 1, 2), main.eligibleSeatIndexes.toSet())
        assertEquals(setOf(1, 2), side.eligibleSeatIndexes.toSet())
        assertEquals(150L, main.amount, "main = 50 × 3")
        assertEquals(300L, side.amount, "side = (200 - 50) × 2")

        // Chip-conservation sanity: total awarded equals total contributed.
        val finalChipsByPlayer = s.seats.associate { it.index to it.stack }
        val totalEnd = finalChipsByPlayer.values.sum()
        assertEquals(450L, totalEnd, "50 + 200 + 200 = 450 conserved")

        // Seat 0 (flush) wins main pot. Seat 1 (Q kicker) beats seat 2 (J
        // kicker) for the side pot.
        assertEquals(150L, finalChipsByPlayer[0], "short stack wins main pot of 150")
        assertEquals(300L, finalChipsByPlayer[1], "Q kicker wins side pot of 300")
        assertEquals(0L, finalChipsByPlayer[2], "J kicker loses both")
    }

    @Test
    fun sidePot_shortStackBustsWhenBigStackHasBetterHand() {
        // Same shape, but seat 0 has the WORST hand. Big stacks each get part
        // of the spoils; seat 0 loses everything.
        val seats = listOf(
            seat(0, stack = 50),
            seat(1, stack = 200),
            seat(2, stack = 200),
        )
        // Seat 0: 8♣ T♥ — total junk relative to the others.
        // Seat 1: A♠ K♠ — flush draw → flush on board.
        // Seat 2: A♥ A♦ — pair of aces (but flush beats pair).
        // Avoid 7 in seat 0 or 4 in board (no wheel).
        val deck = craftDeck(
            seatHoleCards = listOf(
                listOf(Card(Rank.Eight, Suit.Clubs), Card(Rank.Ten, Suit.Hearts)),
                listOf(Card(Rank.Ace, Suit.Spades), Card(Rank.King, Suit.Spades)),
                listOf(Card(Rank.Ace, Suit.Hearts), Card(Rank.Ace, Suit.Diamonds)),
            ),
            community = listOf(
                Card(Rank.Two, Suit.Spades),
                Card(Rank.Five, Suit.Spades),
                Card(Rank.Nine, Suit.Spades),
                Card(Rank.Three, Suit.Clubs),
                Card(Rank.Seven, Suit.Diamonds),
            ),
        )
        var s = startWith(seats, deck, buttonIndex = 2)
        while (s.actingSeatIndex != null) {
            s = apply(s, PlayerIntent.AllIn(s.actingSeatIndex!!))
        }

        // Seat 1 (flush) wins both main and side.
        val finalChips = s.seats.associate { it.index to it.stack }
        assertEquals(0L, finalChips[0])
        assertEquals(450L, finalChips[1], "flush wins everything: 150 main + 300 side")
        assertEquals(0L, finalChips[2])
    }

    // ===========================================================
    // Raise / re-raise mechanics
    // ===========================================================

    @Test
    fun raise_reopensActionForAllPriorCallers() {
        // 4-handed, button=0. Acting order preflop: UTG (3) → button (0) →
        // SB (1) → BB (2). Sequence: UTG calls, button calls, SB calls, BB
        // raises → reopens for UTG.
        val settings = standardSettings()
        var s = GameEngine.startHand(
            settings = settings,
            seats = listOf(seat(0), seat(1), seat(2), seat(3)),
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(42L),
        ).state
        val utg = s.actingSeatIndex!!  // seat 3
        assertEquals(3, utg, "UTG in 4-handed with button=0 is seat 3")
        s = apply(s, PlayerIntent.Call(utg))
        val button = s.actingSeatIndex!!  // seat 0
        s = apply(s, PlayerIntent.Call(button))
        val sb = s.actingSeatIndex!!  // seat 1
        s = apply(s, PlayerIntent.Call(sb))
        val bb = s.actingSeatIndex!!  // seat 2
        s = apply(s, PlayerIntent.Raise(bb, totalAmountThisStreet = 40))

        // Raise reopens action — UTG must act again, and so must the
        // prior callers (button, SB).
        assertEquals(utg, s.actingSeatIndex, "raise reopens action; UTG up first")
        assertEquals(40L, s.currentBetThisStreet)
        assertFalse(s.seatAt(utg).hasActedThisStreet)
        assertFalse(s.seatAt(button).hasActedThisStreet)
        assertFalse(s.seatAt(sb).hasActedThisStreet)
    }

    @Test
    fun raise_belowMinIncrementRefused() {
        // After BB's blind of 10, min raise is currentBet (10) + lastFullRaise
        // (10) = 20. A raise to 15 should fail.
        val settings = standardSettings()
        val s = GameEngine.startHand(
            settings = settings,
            seats = listOf(seat(0), seat(1), seat(2)),
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(7L),
        ).state
        assertFailsWith<IllegalArgumentException> {
            GameEngine.applyIntent(s, PlayerIntent.Raise(s.actingSeatIndex!!, totalAmountThisStreet = 15))
        }
    }

    @Test
    fun minRaise_afterRaise_isCurrentBetPlusLastFullRaise() {
        // UTG raises to 30 (raise of 20 over BB's 10). Next legal raise = 50 (30 + 20).
        var s = GameEngine.startHand(
            settings = standardSettings(),
            seats = listOf(seat(0), seat(1), seat(2)),
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(7L),
        ).state
        val utg = s.actingSeatIndex!!
        s = apply(s, PlayerIntent.Raise(utg, totalAmountThisStreet = 30))
        // SB tries to raise to 40 → only +10 increment, below min.
        val sb = s.actingSeatIndex!!
        assertFailsWith<IllegalArgumentException> {
            GameEngine.applyIntent(s, PlayerIntent.Raise(sb, totalAmountThisStreet = 40))
        }
        // SB raises to 50 → +20 over the 30 = exactly min.
        val ok = GameEngine.applyIntent(s, PlayerIntent.Raise(sb, totalAmountThisStreet = 50))
        assertEquals(50L, ok.state.currentBetThisStreet)
    }

    @Test
    fun allInBelowMinRaise_doesNotChangeLastFullRaiseSize() {
        // When an all-in raises currentBet by less than the previous
        // lastFullRaiseSize (an "incomplete raise"), the engine MUST NOT
        // update lastFullRaiseSize. Why: the next legal re-raise size from
        // a later actor is still measured against the original full raise,
        // not the short-stack all-in.
        //
        // Heads-up; button=0. Stack 100 = exactly minimum (10*BB).
        // - Seat 0 raises to 60 (raise of 50 over BB 10 → lastFullRaise = 50).
        // - Seat 1 has 90 left after the BB. All-in for 90 → contribution
        //   100. Raise = 100 - 60 = 40, which is LESS than lastFullRaise 50.
        // Engine should leave lastFullRaiseSize at 50 (not bump to 40),
        // because the all-in is below the min-raise threshold.
        val seats = listOf(
            seat(0, stack = 100),
            seat(1, stack = 100),
        )
        var s = GameEngine.startHand(
            settings = standardSettings(),
            seats = seats,
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(42L),
        ).state
        // Button (seat 0) acts first preflop heads-up.
        s = apply(s, PlayerIntent.Raise(0, totalAmountThisStreet = 60))
        assertEquals(50L, s.lastFullRaiseSize, "first raise from 10 to 60 = 50")

        s = apply(s, PlayerIntent.AllIn(1))
        // currentBet jumps to 100 (the all-in total), but lastFullRaiseSize
        // stays at 50 — the all-in's 40 was incomplete.
        assertEquals(100L, s.currentBetThisStreet)
        assertEquals(
            50L,
            s.lastFullRaiseSize,
            "incomplete all-in shouldn't bump lastFullRaiseSize",
        )
    }

    // ===========================================================
    // Stack accounting
    // ===========================================================

    @Test
    fun stack_decreasesExactlyByCallAmount() {
        var s = GameEngine.startHand(
            settings = standardSettings(),
            seats = listOf(seat(0), seat(1), seat(2)),
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(42L),
        ).state
        val utgIdx = s.actingSeatIndex!!
        val stackBefore = s.seatAt(utgIdx).stack
        s = apply(s, PlayerIntent.Call(utgIdx))
        val stackAfter = s.seatAt(utgIdx).stack
        // UTG calls the BB (10). Stack should drop by 10.
        assertEquals(stackBefore - 10L, stackAfter)
    }

    @Test
    fun stack_decreasesExactlyByRaiseAmountOverPriorContribution() {
        var s = GameEngine.startHand(
            settings = standardSettings(),
            seats = listOf(seat(0), seat(1), seat(2)),
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(42L),
        ).state
        // SB position (seat 1) contributed 5; raises to 30. Should put 25 more in.
        val utg = s.actingSeatIndex!!
        s = apply(s, PlayerIntent.Call(utg))
        val sbIdx = s.actingSeatIndex!!
        val sbStackBefore = s.seatAt(sbIdx).stack
        s = apply(s, PlayerIntent.Raise(sbIdx, totalAmountThisStreet = 30))
        val sbStackAfter = s.seatAt(sbIdx).stack
        // SB had contributed 5 to street, raised to 30 — added 25 from stack.
        assertEquals(sbStackBefore - 25L, sbStackAfter)
        assertEquals(30L, s.seatAt(sbIdx).contributedThisStreet)
    }

    @Test
    fun fold_doesNotChangeStack() {
        var s = GameEngine.startHand(
            settings = standardSettings(),
            seats = listOf(seat(0), seat(1), seat(2)),
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(42L),
        ).state
        val utg = s.actingSeatIndex!!
        val stackBefore = s.seatAt(utg).stack
        s = apply(s, PlayerIntent.Fold(utg))
        val stackAfter = s.seatAt(utg).stack
        assertEquals(stackBefore, stackAfter, "fold doesn't move chips")
        assertEquals(HandParticipation.Folded, s.seatAt(utg).handParticipation)
    }

    @Test
    fun allIn_emptiesStack() {
        var s = GameEngine.startHand(
            settings = standardSettings(),
            seats = listOf(seat(0, stack = 100), seat(1, stack = 100), seat(2, stack = 100)),
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(1L),
        ).state
        val utg = s.actingSeatIndex!!
        s = apply(s, PlayerIntent.AllIn(utg))
        assertEquals(0L, s.seatAt(utg).stack)
        assertEquals(HandParticipation.AllIn, s.seatAt(utg).handParticipation)
    }

    // ===========================================================
    // Sitting-out / busted seat handling
    // ===========================================================

    @Test
    fun startHand_dealsHoleCardsOnlyToActiveSeats() {
        val seats = listOf(
            seat(0),
            seat(1, status = SeatStatus.SittingOut),
            seat(2),
        )
        val result = GameEngine.startHand(
            settings = standardSettings(),
            seats = seats,
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(42L),
        )
        val deals = result.events.filterIsInstance<GameEvent.HoleCardsDealt>()
        assertEquals(setOf(0, 2), deals.map { it.seatIndex }.toSet())
        // Sitting-out seat: NotDealt status, no hole cards.
        val sittingOut = result.state.seatAt(1)
        assertEquals(HandParticipation.NotDealt, sittingOut.handParticipation)
        assertTrue(sittingOut.holeCards.isEmpty())
    }

    @Test
    fun startHand_skipsSeatsWithZeroStack() {
        val seats = listOf(
            seat(0, stack = 1_000),
            seat(1, stack = 0),
            seat(2, stack = 1_000),
        )
        val result = GameEngine.startHand(
            settings = standardSettings(),
            seats = seats,
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(7L),
        )
        // Seat 1 has zero stack → not dealt.
        assertEquals(HandParticipation.NotDealt, result.state.seatAt(1).handParticipation)
        // Blinds should be on seats 0 and 2.
        val blinds = result.events.filterIsInstance<GameEvent.BlindPosted>()
        assertEquals(setOf(0, 2), blinds.map { it.seatIndex }.toSet())
    }

    @Test
    fun startHand_refusesWithOnlyOneActivePlayer() {
        assertFailsWith<IllegalArgumentException> {
            GameEngine.startHand(
                settings = standardSettings(),
                seats = listOf(seat(0), seat(1, stack = 0), seat(2, status = SeatStatus.SittingOut)),
                handNumber = 1,
                buttonSeatIndex = 0,
                deck = deterministicDeck(1L),
            )
        }
    }

    // ===========================================================
    // Street transitions + community card dealing
    // ===========================================================

    @Test
    fun preflopFold_doesNotDealCommunity() {
        // 3-handed; everyone folds to BB.
        var s = GameEngine.startHand(
            settings = standardSettings(),
            seats = listOf(seat(0), seat(1), seat(2)),
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(42L),
        ).state
        s = apply(s, PlayerIntent.Fold(s.actingSeatIndex!!))  // UTG
        val end = GameEngine.applyIntent(s, PlayerIntent.Fold(s.actingSeatIndex!!))  // SB
        assertEquals(BettingRound.Complete, end.state.street)
        assertTrue(end.state.community.isEmpty(), "no community dealt when all fold preflop")
    }

    @Test
    fun streetAdvanced_event_carriesCommunityCards() {
        var s = GameEngine.startHand(
            settings = standardSettings(),
            seats = listOf(seat(0), seat(1), seat(2)),
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(42L),
        ).state
        var allEvents = emptyList<GameEvent>()
        // 3-handed preflop: UTG (0) calls, SB (1) calls, BB (2) checks the option.
        val intents = listOf(
            PlayerIntent.Call(s.actingSeatIndex!!),       // UTG calls
            PlayerIntent.Call(seatIndex = 1),              // SB calls
            PlayerIntent.Check(seatIndex = 2),             // BB checks option → flop
        )
        for (intent in intents) {
            val r = GameEngine.applyIntent(s, intent)
            s = r.state
            allEvents = allEvents + r.events
        }
        // Flop StreetAdvanced should have 3 community cards.
        val flopAdvanced = allEvents.filterIsInstance<GameEvent.StreetAdvanced>()
            .first { it.street == BettingRound.Flop }
        assertEquals(3, flopAdvanced.communityCards.size)
        assertEquals(s.community, flopAdvanced.communityCards)
    }

    @Test
    fun communityCards_drawnInFlopTurnRiverOrder() {
        // Verify the engine deals the next 3 deck cards as the flop, then 1
        // turn, then 1 river — no burn cards. Craft a known deck and read the
        // community back.
        val seats = listOf(seat(0, stack = 100), seat(1, stack = 100))
        val flopTurnRiver = listOf(
            Card(Rank.Two, Suit.Spades),
            Card(Rank.Five, Suit.Diamonds),
            Card(Rank.Nine, Suit.Clubs),
            Card(Rank.Jack, Suit.Hearts),
            Card(Rank.Three, Suit.Spades),
        )
        val deck = craftDeck(
            seatHoleCards = listOf(
                listOf(Card(Rank.Ace, Suit.Spades), Card(Rank.King, Suit.Spades)),
                listOf(Card(Rank.Seven, Suit.Hearts), Card(Rank.Seven, Suit.Clubs)),
            ),
            community = flopTurnRiver,
        )
        var s = startWith(seats, deck)
        s = apply(s, PlayerIntent.AllIn(s.actingSeatIndex!!))
        val r = GameEngine.applyIntent(s, PlayerIntent.Call(s.actingSeatIndex!!))

        assertEquals(flopTurnRiver, r.state.community, "community = flop ++ turn ++ river in deck order")
    }

    // ===========================================================
    // BB option preflop
    // ===========================================================

    @Test
    fun bbOption_canCheckWhenEveryoneJustCalled() {
        // 3-handed. UTG calls, SB calls, BB checks → flop.
        var s = GameEngine.startHand(
            settings = standardSettings(),
            seats = listOf(seat(0), seat(1), seat(2)),
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(42L),
        ).state
        s = apply(s, PlayerIntent.Call(s.actingSeatIndex!!))  // UTG
        s = apply(s, PlayerIntent.Call(s.actingSeatIndex!!))  // SB
        // Now BB to act — should be allowed to check.
        val bbAction = GameEngine.applyIntent(s, PlayerIntent.Check(s.actingSeatIndex!!))
        assertEquals(BettingRound.Flop, bbAction.state.street, "BB checks the option, hand goes to flop")
    }

    @Test
    fun bbOption_canRaiseInsteadOfChecking() {
        var s = GameEngine.startHand(
            settings = standardSettings(),
            seats = listOf(seat(0), seat(1), seat(2)),
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(42L),
        ).state
        s = apply(s, PlayerIntent.Call(s.actingSeatIndex!!))
        s = apply(s, PlayerIntent.Call(s.actingSeatIndex!!))
        val bb = s.actingSeatIndex!!
        val raised = GameEngine.applyIntent(s, PlayerIntent.Raise(bb, totalAmountThisStreet = 30))
        assertEquals(BettingRound.Preflop, raised.state.street, "BB raise keeps action preflop")
        assertEquals(30L, raised.state.currentBetThisStreet)
    }

    // ===========================================================
    // Acting-out-of-turn / wrong-actor protection
    // ===========================================================

    @Test
    fun applyIntent_fromFoldedSeat_throws() {
        var s = GameEngine.startHand(
            settings = standardSettings(),
            seats = listOf(seat(0), seat(1), seat(2)),
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(42L),
        ).state
        val utg = s.actingSeatIndex!!
        s = apply(s, PlayerIntent.Fold(utg))
        // Now seat 1 (SB) is acting. Try to make folded seat act again.
        assertFailsWith<IllegalArgumentException> {
            GameEngine.applyIntent(s, PlayerIntent.Call(utg))
        }
    }

    @Test
    fun applyIntent_afterHandComplete_throws() {
        var s = GameEngine.startHand(
            settings = standardSettings(),
            seats = listOf(seat(0), seat(1)),
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(42L),
        ).state
        s = apply(s, PlayerIntent.Fold(s.actingSeatIndex!!))
        assertEquals(BettingRound.Complete, s.street)
        assertFailsWith<IllegalArgumentException> {
            GameEngine.applyIntent(s, PlayerIntent.Check(seatIndex = 1))
        }
    }

    // ===========================================================
    // Event ordering
    // ===========================================================

    @Test
    fun events_handStartedAlwaysComesFirst() {
        val result = GameEngine.startHand(
            settings = standardSettings(),
            seats = listOf(seat(0), seat(1), seat(2)),
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(42L),
        )
        assertTrue(result.events.first() is GameEvent.HandStarted)
    }

    @Test
    fun events_handEndedComesLastOnFoldAround() {
        var s = GameEngine.startHand(
            settings = standardSettings(),
            seats = listOf(seat(0), seat(1), seat(2)),
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(42L),
        ).state
        s = apply(s, PlayerIntent.Fold(s.actingSeatIndex!!))
        val r = GameEngine.applyIntent(s, PlayerIntent.Fold(s.actingSeatIndex!!))
        assertTrue(r.events.last() is GameEvent.HandEnded)
    }

    @Test
    fun events_potAwardedFiresBeforeHandEnded() {
        var s = GameEngine.startHand(
            settings = standardSettings(),
            seats = listOf(seat(0), seat(1), seat(2)),
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(42L),
        ).state
        s = apply(s, PlayerIntent.Fold(s.actingSeatIndex!!))
        val r = GameEngine.applyIntent(s, PlayerIntent.Fold(s.actingSeatIndex!!))
        val pot = r.events.indexOfFirst { it is GameEvent.PotAwarded }
        val end = r.events.indexOfFirst { it is GameEvent.HandEnded }
        assertTrue(pot >= 0 && end > pot, "PotAwarded fires before HandEnded")
    }

    // ===========================================================
    // Heads-up specifics
    // ===========================================================

    @Test
    fun headsUp_sbCanLimpToCompleteBB() {
        var s = GameEngine.startHand(
            settings = standardSettings(),
            seats = listOf(seat(0), seat(1)),
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(42L),
        ).state
        // Heads-up: button posts SB (seat 0). Acts first preflop. Calls 5
        // more to complete the BB.
        assertEquals(0, s.actingSeatIndex)
        s = apply(s, PlayerIntent.Call(0))
        // BB's turn — should be able to check.
        assertEquals(1, s.actingSeatIndex)
        val toFlop = GameEngine.applyIntent(s, PlayerIntent.Check(1))
        assertEquals(BettingRound.Flop, toFlop.state.street)
    }

    @Test
    fun headsUp_postflopFirstToAct_isNotTheButton() {
        var s = GameEngine.startHand(
            settings = standardSettings(),
            seats = listOf(seat(0), seat(1)),
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(42L),
        ).state
        s = apply(s, PlayerIntent.Call(0))
        s = apply(s, PlayerIntent.Check(1))
        // Now on flop — non-button (seat 1) acts first.
        assertEquals(BettingRound.Flop, s.street)
        assertEquals(1, s.actingSeatIndex, "non-button acts first postflop")
    }

    // ===========================================================
    // GameState invariants
    // ===========================================================

    @Test
    fun gameState_handNumberPreservedFromStart() {
        val s = GameEngine.startHand(
            settings = standardSettings(),
            seats = listOf(seat(0), seat(1), seat(2)),
            handNumber = 17,
            buttonSeatIndex = 0,
            deck = deterministicDeck(42L),
        ).state
        assertEquals(17, s.handNumber)
        // After actions, handNumber stays.
        val after = GameEngine.applyIntent(s, PlayerIntent.Fold(s.actingSeatIndex!!)).state
        assertEquals(17, after.handNumber)
    }

    @Test
    fun gameState_buttonSeatIndexPreservedAcrossActions() {
        val s = GameEngine.startHand(
            settings = standardSettings(),
            seats = listOf(seat(0), seat(1), seat(2)),
            handNumber = 1,
            buttonSeatIndex = 1,
            deck = deterministicDeck(42L),
        ).state
        assertEquals(1, s.buttonSeatIndex)
        val after = GameEngine.applyIntent(s, PlayerIntent.Fold(s.actingSeatIndex!!)).state
        assertEquals(1, after.buttonSeatIndex)
    }

    @Test
    fun gameState_actingSeatIndexIsNullWhenHandComplete() {
        var s = GameEngine.startHand(
            settings = standardSettings(),
            seats = listOf(seat(0), seat(1)),
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(42L),
        ).state
        s = apply(s, PlayerIntent.Fold(0))
        assertEquals(BettingRound.Complete, s.street)
        assertNull(s.actingSeatIndex)
    }
}
