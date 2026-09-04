# Testing PlayPokerScreen

Companion to `playpokerscreen-review.md`. This is about coverage shape, not more tests.

## Start here: the infrastructure already exists

`features/room/impl/src/androidUnitTest/.../ui/PlayPokerScreenTest.kt` has **14 Robolectric +
compose-uiTest tests over this screen**, all green in ~18s, running in CI today. `features/onboarding/impl`
has its own suite. Both modules already wire `compose-uiTest`, `uiTestManifest`, `robolectric` and
`unitTests.isIncludeAndroidResources`.

Several docs claimed otherwise for months, copied from a stale status line. Corrected in `21b6f7bc`.

**The problem is not that the machinery is missing. It is that all 14 tests render a single state.**
None crosses a hand boundary. That is exactly the seam tap-to-flip fell through, and it is the one
thing worth changing.

Also already available and under-used: `features/room/impl/src/commonTest/.../harness/PokerScenario.kt`
drives a **real** `PlayPokerViewModel` over a **real** `LocalBotsSession` with a scripted bot
decider, a stacked deck, and multi-hand advance. `commonTest` and `androidUnitTest` compile into
one classpath, so that harness is already visible to the Compose tests. Rendering on top of it is
the whole job — no new module, source set or dependency.

## The finite state machine

Six concurrent regions. Flattening them gives ~10⁴ states and hides the couplings, which is where
the bugs actually live.

**A — Table projection.** `Loading → Active`, where `Active` is a product of: street
(`Preflop→Flop→Turn→River→Showdown→Complete`) × turn (`HumanTurn` / `BotActing` / `NoActor`) ×
outcome (`Live` / `WonByFold` / `WonAtShowdown`) × local seat (`Seated` / `WaitingToBeDealtIn` /
`Busted`) × money tier (`SoloBots` / `MpPracticeBotsPresent` / `MpPracticeBotsOnly` /
`MpSubsidizedBots` / `MpRealChips`) × connection.

**`handNumber` is the axis that re-arms every per-hand `remember` on the screen, and it is the one
the existing tests never cross.**

**B — End-of-hand disposition.** The densest branch tree on the screen, one test today:

```
handResult != null
├─ winnerWaitingOnRebuyGrace ──> no dialog, banner only          [MP-22]
├─ humanBust && realMP ────────> MultiplayerBustDialog {Rebuy | BuyChips | Leave}
├─ humanBust && !realMP
│    ├─ showdownReached && !acknowledged ──> ShowdownDialog ──> BustDialog   [GAME-18]
│    └─ else ─────────────────────────────> BustDialog
├─ !realChipsAtStake ──────────> ShowdownDialog
└─ realChipsAtStake && !bust ──> NO DIALOG: felt countdown + leave-with-winnings
```

with `onDismiss` a further three-way on `isBots` × `recentlyEarned` × `awaitingHandEndAchievements`.

**C — Leave.** `requiresLeaveConfirmation = handInProgress || realMoneySeat`, deliberately not using
`isRealMultiplayer` so it still fires on a degraded table (MP-31).

**D — Modal surfaces.** 15 screen-local `remember`s + 4 VM-owned, **mutually non-exclusive**. Only
`actionSheetOpen` has a cross-state guard (force-closed on turn change). Nothing else closes on a
hand boundary.

**E — Reward freeze.** `xpFrozen` pins displayed XP/stack while a result is up, releases into a
particle burst.

**F — `PlayerArea` locals.** Where two of the three real bugs lived: `manuallyFacedown` (tap toggle,
reset per hand), `dragOffsetY` (swipe-fold), and `HoleCardSlot`'s `arrived→revealed→settled`.

**G — MP match-over.** Rebuy grace countdown → terminal result dialog.

**H — Tutorial.** Reuses the screen with the help button nulled and leave-confirm disabled.

## What tests can and cannot catch

No single layer catches all three of the bugs that actually shipped.

| Layer | Status | Silent multi-hand binding | Per-frame recomposition | Unkeyed derived state |
|---|---|---|---|---|
| VM/state unit + `PokerScenario` | exists | No — state was always correct | No | No |
| Compose, single state (14 tests) | exists | **No** — needs two hands | No | No |
| **Compose driven by `PokerScenario`** | **build** | **Yes** | No | Symptom yes |
| `AnimatedStateReadInComposition` detekt rule | written, **doesn't run** (ENG-54) | No | **Yes, statically, forever** | Partial |
| Perfetto + composition tracing | manual, documented | No | **Yes, empirically** | Yes |

**Be blunt about the middle column.** Robolectric has no RenderThread and no glyph cache, and
`waitForIdle()` deliberately drains recompositions to quiescence. A composable recomposing 471
times and one recomposing 6 produce *identical assertions*. The functional test passes while the
app ANRs.

Counting recompositions in-test with a `SideEffect` probe is possible but not recommended as the
primary defence: under a virtual clock the count reflects how many frames the harness chose to run,
not production cost. A proxy that drifts is worse than nothing, because it reads as coverage — the
same argument `docs/todo.md` already makes about the dead detekt rule.

## Plan

**Stage 0 — interop spike (1-2h).** Render `PlayPokerScreen` on top of a `SoloScenarioBuilder`
inside `runComposeUiTest`. The unknown is clock interop: the scenario uses `TestScope` virtual time
while Compose's Robolectric harness pumps the main looper. **Take the wall-clock path first** —
real dispatchers, `waitUntil(10_000)`, accept ~1.3-3s per hand from `HAND_START_GRACE_MS`. That
designs the clock problem out entirely. Kill criterion: if neither path works in two hours, fall
back to hoisted-state tests, which still catch the multi-hand bug.

**Stage 1 — three regression tests (4-6h).** Best value per hour in the plan.
- `tapToFlip_stillTogglesOnTheSecondHand` — the actual bug. `PlayingCard` already emits rank and
  suit as real `Text` while `PlayingCardBack` is Canvas-only, so face-up/face-down is assertable
  with **zero production changes** and no test tags.
- `newHand_resetsManualFlipToFaceUp` — guards the reset, so nobody "fixes" the first test by
  re-adding the key and dropping the reset.
- `swipeFold_thenNewHand_dealsCardsAtRest` — GAME-10, same family, nearly free once the driver exists.

**Stage 2 — end-of-hand matrix (4-6h).** Region B off hoisted state, no session needed. Priority:
the real-chips felt-countdown branch (untested), the rebuy-grace suppression (MP-22), quick-buy
overlaying the bust dialog, and the fast-dismiss achievement hold — pure race logic, currently
untested, exactly the shape that ships.

**Stage 3 — make the detekt rule run (2-4h, hard timebox).** ENG-54. **The single cheapest
bug-catching hour available**: one version bump off `2.0.0-alpha.5` and it flags every future
instance of the ENG-49 pattern across the whole codebase, in CI, including the one nobody has found
yet. If the bump fights back, stop and file it.

**Not building.** Macrobenchmark (needs a device in CI, which is JVM-only today; emulator
`frameTimingMetric` is noisy enough that you'd tune thresholds instead of shipping). Screenshot
tests. The spike doc's `TestAppComponent` — worth doing eventually for **navigation**, but it would
not have caught any of the three bugs, so it is orthogonal, not a prerequisite.

Keep the tier under ~30 tests. `:apps:integration` owns replaying gameplay; this tier owns the
binding between state and pixels.

## Two mechanical notes

`LocalInspectionMode` is false under Robolectric, so deal animations actually run and every test
needs a settle wait (~890ms for `HoleCardSlot`). Put it in a helper.

There are **zero `testTag` calls in the repo** and the felt is nearly semantics-free. Stage 1 needs
none. Stage 2 will. Agree a convention before scattering them.
