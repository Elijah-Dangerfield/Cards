package com.dangerfield.cards.libraries.gameplay

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Property-based invariant coverage for [GameEngine] (testing-plan Round 3).
 *
 * Where [GameEngineTest] / [GameEngineAdvancedTest] pin specific crafted-deck
 * scenarios, this suite drives **hundreds of randomized-but-legal full hands**
 * across varying seat counts, stacks, and button positions, asserting the
 * engine's load-bearing invariants hold at *every* step of *every* hand:
 *
 *  - **Stack conservation** — no chip is ever created or destroyed.
 *  - **Sequence monotonicity** — emitted event sequences strictly increase and
 *    the final state's `lastSequence` matches the last event.
 *  - **Acting-seat validity** — `actingSeatIndex` always points at a seat that
 *    can actually act, or is null at hand end.
 *  - **Settlement equals winnings** — every contributed chip is awarded back.
 *  - **Pot conservation + nested eligibility** — pots sum to the total
 *    contributed and side-pot eligibility shrinks monotonically.
 *  - **Hole-card scrub correctness** — `scrubbedFor` never leaks a non-viewer's
 *    hole cards before showdown, or a folded seat's cards at showdown.
 *
 * The driver is deterministic given a seed (the engine is pure and the deck is
 * seeded), so a failing property reproduces exactly from the printed seed.
 *
 * NOT covered here: specific winner determination (crafted decks in
 * [GameEngineAdvancedTest]) and wire serialization (server/contract tests).
 */
class GameEnginePropertyTest {

    private val settings = RoomSettings(
        smallBlind = 5,
        bigBlind = 10,
        startingStack = 1_000,
        maxSeats = 9,
        turnTimerSeconds = 30,
    )

    private val stackChoices = longArrayOf(40, 80, 200, 1_000)

    private data class HandTrace(
        val startingTotal: Long,
        val finalState: GameState,
        val allEvents: List<GameEvent>,
        val intermediateStates: List<GameState>,
    )

    /**
     * Picks a uniformly-random *legal* intent for [seat] given the live betting
     * context, so `applyIntent` never has to reject. The legality reasoning here
     * mirrors `GameEngine.resolveAction`; if it drifts, these tests start
     * throwing `IllegalArgumentException` rather than silently passing.
     */
    private fun chooseLegalIntent(state: GameState, seat: Seat, rng: Random): PlayerIntent {
        val toCall = state.currentBetThisStreet - seat.contributedThisStreet
        val options = mutableListOf<PlayerIntent>(PlayerIntent.Fold(seat.index))
        if (toCall == 0L) {
            options += PlayerIntent.Check(seat.index)
            if (state.currentBetThisStreet == 0L) {
                if (seat.stack >= state.lastFullRaiseSize) {
                    options += PlayerIntent.Bet(seat.index, state.lastFullRaiseSize)
                }
            } else {
                val total = state.currentBetThisStreet + state.lastFullRaiseSize
                if (total - seat.contributedThisStreet <= seat.stack) {
                    options += PlayerIntent.Raise(seat.index, total)
                }
            }
        } else {
            options += PlayerIntent.Call(seat.index)
            val total = state.currentBetThisStreet + state.lastFullRaiseSize
            if (total - seat.contributedThisStreet <= seat.stack) {
                options += PlayerIntent.Raise(seat.index, total)
            }
        }
        options += PlayerIntent.AllIn(seat.index)
        return options[rng.nextInt(options.size)]
    }

    private fun playRandomHand(seed: Long): HandTrace {
        val rng = Random(seed)
        val numSeats = 2 + rng.nextInt(5)
        val seats = List(numSeats) { i ->
            Seat(
                index = i,
                playerId = "p$i",
                displayName = "P$i",
                stack = stackChoices[rng.nextInt(stackChoices.size)],
                seatStatus = SeatStatus.Active,
                handParticipation = HandParticipation.InHand,
            )
        }
        val startingTotal = seats.sumOf { it.stack }
        val button = rng.nextInt(numSeats)
        val start = GameEngine.startHand(
            settings = settings,
            seats = seats,
            handNumber = 1,
            buttonSeatIndex = button,
            deck = deterministicDeck(seed),
        )
        var state = start.state
        val events = start.events.toMutableList()
        val intermediates = mutableListOf(state)
        var guard = 0
        while (state.actingSeatIndex != null && guard++ < 1_000) {
            val acting = state.seatAt(state.actingSeatIndex)
            val result = GameEngine.applyIntent(state, chooseLegalIntent(state, acting, rng))
            state = result.state
            events += result.events
            intermediates += state
        }
        return HandTrace(startingTotal, state, events, intermediates)
    }

    @Test
    fun stackConservation_holdsAtEveryStepOfEveryHand() {
        for (seed in 0L until 300L) {
            val trace = playRandomHand(seed)
            for (state in trace.intermediateStates) {
                val onTable = state.seats.sumOf { it.contributedThisHand }
                val stacks = state.seats.sumOf { it.stack }
                if (state.street == BettingRound.Complete) {
                    assertEquals(
                        trace.startingTotal,
                        stacks,
                        "seed=$seed: pot must be fully returned to stacks at hand end",
                    )
                } else {
                    assertEquals(
                        trace.startingTotal,
                        stacks + onTable,
                        "seed=$seed: stacks + chips-on-table must equal starting total",
                    )
                }
            }
        }
    }

    @Test
    fun eventSequences_strictlyIncreaseAndMatchFinalState() {
        for (seed in 0L until 300L) {
            val trace = playRandomHand(seed)
            val sequences = trace.allEvents.map { it.sequence }
            assertTrue(sequences.isNotEmpty(), "seed=$seed: a hand always emits events")
            for (i in 1 until sequences.size) {
                assertTrue(
                    sequences[i] > sequences[i - 1],
                    "seed=$seed: sequences must strictly increase ($sequences)",
                )
            }
            assertEquals(
                sequences.last(),
                trace.finalState.lastSequence,
                "seed=$seed: final lastSequence must match the last emitted event",
            )
        }
    }

    @Test
    fun actingSeat_alwaysPointsAtASeatThatCanAct() {
        for (seed in 0L until 300L) {
            val trace = playRandomHand(seed)
            for (state in trace.intermediateStates) {
                val acting = state.actingSeatIndex ?: continue
                assertTrue(
                    state.seatAt(acting).canAct,
                    "seed=$seed: actingSeatIndex=$acting must reference a seat that canAct",
                )
            }
            assertEquals(
                BettingRound.Complete,
                trace.finalState.street,
                "seed=$seed: a hand with no acting seat must be Complete",
            )
            assertEquals(null, trace.finalState.actingSeatIndex, "seed=$seed")
        }
    }

    @Test
    fun settlement_awardsExactlyTheContributedChips() {
        for (seed in 0L until 300L) {
            val trace = playRandomHand(seed)
            val totalContributed = trace.finalState.seats.sumOf { it.contributedThisHand }
            val awarded = trace.allEvents
                .filterIsInstance<GameEvent.PotAwarded>()
                .sumOf { it.amount }
            val potTotal = trace.finalState.pots.sumOf { it.amount }
            assertEquals(totalContributed, potTotal, "seed=$seed: pots must hold every contributed chip")
            assertEquals(totalContributed, awarded, "seed=$seed: every contributed chip must be awarded")
        }
    }

    @Test
    fun pots_conserveChipsAndNestEligibilityMonotonically() {
        for (seed in 0L until 300L) {
            val trace = playRandomHand(seed)
            val pots = trace.finalState.pots
            val contributed = trace.finalState.seats.sumOf { it.contributedThisHand }
            assertEquals(contributed, pots.sumOf { it.amount }, "seed=$seed: pot conservation")
            for (pot in pots) {
                for (idx in pot.eligibleSeatIndexes) {
                    assertTrue(
                        trace.finalState.seatAt(idx).isInHand,
                        "seed=$seed: only in-hand seats are pot-eligible",
                    )
                }
            }
            for (i in 1 until pots.size) {
                assertTrue(
                    pots[i - 1].eligibleSeatIndexes.toSet()
                        .containsAll(pots[i].eligibleSeatIndexes.toSet()),
                    "seed=$seed: side-pot eligibility must be a subset of the prior pot's",
                )
            }
        }
    }

    /**
     * Drives a random hand but, after a few steps, forfeits a random in-hand
     * seat (a mid-hand leave). Asserts the same load-bearing invariants still
     * hold and that the hand always reaches a clean terminal state — the
     * regression guard for the "stuck — nobody's turn" bug, where a leaving
     * actor used to strand `actingSeatIndex` on a gone seat.
     */
    @Test
    fun forfeit_preservesInvariants_andAlwaysReachesCleanCompletion() {
        for (seed in 0L until 300L) {
            val trace = playRandomHandWithForfeit(seed)

            for (state in trace.intermediateStates) {
                val onTable = state.seats.sumOf { it.contributedThisHand }
                val stacks = state.seats.sumOf { it.stack }
                if (state.street == BettingRound.Complete) {
                    assertEquals(trace.startingTotal, stacks, "seed=$seed: pot returned to stacks at hand end")
                } else {
                    assertEquals(
                        trace.startingTotal,
                        stacks + onTable,
                        "seed=$seed: stacks + chips-on-table must equal starting total",
                    )
                }
                val acting = state.actingSeatIndex
                if (acting != null) {
                    assertTrue(
                        state.seatAt(acting).canAct,
                        "seed=$seed: actingSeatIndex=$acting must reference a seat that canAct",
                    )
                }
            }

            // The hand always terminates: the driver loops until there's no
            // actor, so a null actor must mean Complete (never a stall).
            assertEquals(null, trace.finalState.actingSeatIndex, "seed=$seed: no stranded actor")
            assertEquals(
                BettingRound.Complete,
                trace.finalState.street,
                "seed=$seed: a hand with no acting seat must be Complete",
            )

            val contributed = trace.finalState.seats.sumOf { it.contributedThisHand }
            val awarded = trace.allEvents.filterIsInstance<GameEvent.PotAwarded>().sumOf { it.amount }
            assertEquals(contributed, awarded, "seed=$seed: every contributed chip must be awarded")
        }
    }

    private fun playRandomHandWithForfeit(seed: Long): HandTrace {
        val rng = Random(seed)
        val numSeats = 2 + rng.nextInt(5)
        val seats = List(numSeats) { i ->
            Seat(
                index = i,
                playerId = "p$i",
                displayName = "P$i",
                stack = stackChoices[rng.nextInt(stackChoices.size)],
                seatStatus = SeatStatus.Active,
                handParticipation = HandParticipation.InHand,
            )
        }
        val startingTotal = seats.sumOf { it.stack }
        val button = rng.nextInt(numSeats)
        val start = GameEngine.startHand(
            settings = settings,
            seats = seats,
            handNumber = 1,
            buttonSeatIndex = button,
            deck = deterministicDeck(seed),
        )
        var state = start.state
        val events = start.events.toMutableList()
        val intermediates = mutableListOf(state)
        val stepsBeforeForfeit = rng.nextInt(4)
        var steps = 0
        var forfeited = false
        var guard = 0
        while (state.actingSeatIndex != null && guard++ < 1_000) {
            if (!forfeited && steps >= stepsBeforeForfeit) {
                val candidates = state.seats.filter { it.handParticipation == HandParticipation.InHand }
                if (candidates.isNotEmpty()) {
                    val victim = candidates[rng.nextInt(candidates.size)]
                    val result = GameEngine.forfeitSeat(state, victim.index)
                    state = result.state
                    events += result.events
                    intermediates += state
                    forfeited = true
                    // Idempotency: forfeiting the same seat again must be a no-op.
                    val again = GameEngine.forfeitSeat(state, victim.index)
                    assertEquals(state, again.state, "seed=$seed: forfeit must be idempotent")
                    assertTrue(again.events.isEmpty(), "seed=$seed: a repeat forfeit emits nothing")
                    continue
                }
            }
            val actingIdx = state.actingSeatIndex ?: break
            val acting = state.seatAt(actingIdx)
            val result = GameEngine.applyIntent(state, chooseLegalIntent(state, acting, rng))
            state = result.state
            events += result.events
            intermediates += state
            steps++
        }
        return HandTrace(startingTotal, state, events, intermediates)
    }

    @Test
    fun scrub_neverLeaksHiddenHoleCards() {
        for (seed in 0L until 300L) {
            val trace = playRandomHand(seed)
            for (state in trace.intermediateStates) {
                val viewers = state.seats.map { it.index } + listOf(-1)
                for (viewer in viewers) {
                    val scrubbed = state.scrubbedFor(viewer)
                    val showdownLike = scrubbed.street == BettingRound.Showdown ||
                        scrubbed.street == BettingRound.Complete
                    for (seat in scrubbed.seats) {
                        if (seat.index == viewer) {
                            assertEquals(
                                state.seatAt(viewer).holeCards,
                                seat.holeCards,
                                "seed=$seed: the viewer always sees their own cards",
                            )
                            continue
                        }
                        if (seat.holeCards.isNotEmpty()) {
                            assertTrue(
                                showdownLike && seat.isInHand,
                                "seed=$seed: seat ${seat.index} cards leaked to viewer $viewer " +
                                    "(street=${scrubbed.street}, participation=${seat.handParticipation})",
                            )
                        }
                    }
                }
            }
        }
    }
}
