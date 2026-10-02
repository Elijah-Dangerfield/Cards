# 2026-10-02 — "A hand won when it shouldn't have"

**Source:** owner-relayed player report. A friend playing multiplayer said the win conditions
looked wrong — a hand was shown as won by someone who should not have won it.

**Status:** root-caused and fixed in this session. Evaluator exonerated; the defect was in the
client's fallback winner projection.

## What was NOT wrong

The hand evaluator. Before touching anything, `HandEvaluator` was checked against an
independently written reference (13-bit rank mask for straights, suit histogram for flushes,
a packed integer for the tiebreak — a different algorithm from the combination-enumeration one
in `HandEvaluator`). See `HandEvaluatorDifferentialTest`:

- 30,000 random two-player showdowns off a shared board: the two implementations agreed on the
  winner every time.
- 20,000 random 7-card hands: identical category every time.
- 20,000 hands: `bestFive` is a real 5-card subset of the input and re-evaluates to the same
  category and tiebreakers (so the highlighted cards always match the claimed hand).
- Comparison is reflexive and antisymmetric across 400 hands pairwise.
- Equal ranks in different suits tie exactly, so split pots split.

`GameEngine.runShowdown` and `PotBuilder` were read against this and are also correct, including
side-pot eligibility and the odd chip. **The server never awarded the wrong player.** No chips
were ever misallocated.

## What was wrong

`TableUiState.synthesizeHandResult` — the client-side fallback used when the transient
`GameEvent.HandEnded` never arrives but the snapshot is already `BettingRound.Complete`
(the MP-26 / MP-25 family: a lost event, one rolled out of the replay window, or one raced by
reconnect).

It named the winner from `Pot.eligibleSeatIndexes`:

```kotlin
val winnerSeats = gameState.pots.flatMap { it.eligibleSeatIndexes }.distinct()
    .ifEmpty { contenders.map { it.index } }
val perWinner = potTotal / winnerSeats.size
```

Eligibility is **who could win the pot**, not who did. At any contested showdown every seat that
reached the river is eligible for the main pot. So a two-handed showdown badged *both* players as
winners and split the pot between them; a three-way showdown badged all three. `byFold` is
`contenders.size <= 1`, so it correctly read as a showdown — and then declared everyone the winner
of it.

That is exactly the reported symptom. The banner is wrong while the stacks (which come from the
authoritative snapshot) are right, which is why it reads as "a hand won when it shouldn't have"
rather than as missing chips.

The function was written for the narrow heads-up-timeout case its doc comment describes, where a
single contender remains. The guard was only `handComplete`, so it also caught every genuine
showdown whose `HandEnded` went missing.

Secondary: the odd chip was dropped (`potTotal / n` with no remainder), and `handRank` was always
null so the banner could not name the hand.

## The fix

`synthesizeHandResult` now evaluates instead of inferring. `scrubbedFor` deliberately keeps every
in-hand seat's hole cards at `Showdown`/`Complete` so the reveal UI works, so the cards needed to
pick the winner are already on the client. It runs the same per-pot comparison the engine does,
including the odd chip going to the first tied seat left of the button, and fills in `handRank`.

Preserved behaviour:
- One contender left is still an uncontested `byFold` win.
- A view with no evaluable cards (fully scrubbed) still falls back to eligibility, so the MP-26
  dead-table fix holds — a wrong-looking banner beats no Next Hand path.
- A real `HandEnded` still wins; the server stays authoritative.

Guarded by `ShowdownWinnerSynthesisTest` (8 cases: contested heads-up, three-way, genuine tie,
odd chip, side pots, fold win, scrubbed fallback, live-event precedence). Four of those failed
against the old code.

## Files

- `features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/TableUiState.kt`
- `features/room/impl/src/commonTest/kotlin/com/cards/features/room/impl/ShowdownWinnerSynthesisTest.kt`
- `libraries/gameplay/src/commonTest/kotlin/com/cards/libraries/gameplay/HandEvaluatorDifferentialTest.kt`

## Note for whoever reads this next

The report said "win conditions", which points straight at the evaluator. It was the right place
to look and the wrong place to stop — the bug was two layers up, in code that guesses a result
when an event goes missing. The differential test is worth keeping precisely because it makes
"the evaluator is fine" a fact rather than an impression, which is what made it cheap to move on.
