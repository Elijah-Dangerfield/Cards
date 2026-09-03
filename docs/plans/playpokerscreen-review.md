# PlayPokerScreen review, 2026-09-03

Four passes over `features/room/impl/` (18.5k lines; the screen is 2032, the ViewModel 1420): one
hunting correctness bugs, one hunting Compose performance, one designing test coverage, and one
whose only job was to **attack the other three**.

That last pass is the reason to trust this list. It killed five findings outright and downgraded
five more. What follows is what survived being argued against.

**One survivor did not survive measurement.** Item 8 below was the highest-ranked finding on the
list and is simply false — the Compose compiler's own reports say so. It stayed on the list through
four passes because every pass reasoned about stability from the source rather than measuring it.
Worth remembering when reading the rest: the items with a measurement behind them (2, 5, 7) are on
firmer ground than the ones argued from reading.

## Review criteria

Derived from what actually broke this week, not from a generic checklist. Every criterion has a
corpse behind it.

1. **State ownership and staleness.** Does every lambda's key list match the lifetime of what it
   captures? A callback outliving a keyed `remember` it writes to is the tap-to-flip bug. Highest
   value because it fails *silently* — nothing crashes, a feature just stops.
2. **Recomposition cost.** Animated values read during composition. Four instances found; three
   were fixed, and the sweep still missed one.
3. **Modal state machine integrity.** Nineteen overlapping surfaces, fifteen of them screen-local
   `remember`s. Can two show at once? Can any be entered with no exit? What resets on a new hand?
4. **Lifecycle and coroutines.** Effects keyed on identity that changes every recomposition, or on
   `Unit` when they shouldn't be.
5. **Crash surface.** `!!` / `first()` / `single()` on seat collections.
6. **Testability.** What is untestable, and why.

## What survived, ranked by (impact × likelihood) ÷ effort

| # | Finding | Site | Why it matters |
|---|---|---|---|
| 1 | **Emote ticker never stops** | `EmojiTray.kt:86,307` | `active = cooldownEndsAtEpochMs > 0L` means "ever sent an emote", not "still cooling". Nothing writes 0 back, `LaunchedEffect(active)` never re-keys, so `while(true){delay(250)}` writes snapshot state 4×/sec for the rest of the session. A **fourth** infinite composition-scope read the ENG-49 sweep missed, and free to trigger. |
| 2 | **Turn countdown ring** | `TurnCountdownRing.kt:82` | `progress.value` read in composition, so every frame mints a new `Canvas` draw lambda. ~1800 recompositions per 30s MP turn, on essentially every turn of every MP hand. One line. |
| 3 | **Opponent seat scale** | `OpponentSeat.kt:123` | `by` on a value consumed only inside a `graphicsLayer`. One word, fires on every turn change. **Caveat: will not move the RenderThread number** — the seat text is still under a per-frame-changing scale, and glyph reuse doesn't survive that. |
| 4 | **Hole cards keyed on `Card`** | `PlayerArea.kt:475` | `Card` is a data class, so an identical card next hand reuses the group and `settled` stays true — that card renders face-up with no deal-in. 3.8%, so ~1 hand in 26. `BoardArea.kt:91` already does `key(table.handNumber)` correctly. |
| 5 | **Swipe-fold drag progress** | `PlayerArea.kt:196` | Same anti-pattern as the measured 471→16 fix, in the same file, one screenful below the comment explaining why not to. Rarer (only during a fold) but identical shape. |
| 6 | **Stale XP on bust** | `PlayPokerViewModel.kt:1008` | `lastHandXpAwarded` is cleared only by `RequestNextHand`, which real-chip tables never dispatch. `MultiplayerBustDialog` mounts before the award coroutine settles, so every real-chip bust briefly shows the previous hand's XP. Fix: clear it in `HandEndAchievementsPending` beside `recentlyEarned`. |
| 7 | **Per-frame text content** | `BoardArea.kt:124` + `AnimatedNumberText.kt:77,113` | Both feed a **new String every frame** into large text — the pot ship for 800ms, the chip odometer for 700ms, overlapping at hand end. This is the exact glyph-cache path from the ANR traces. Do both together or neither. |
| 8 | ~~**`TableUiState` instability**~~ | `TableUiState.kt:29,332` | **Wrong, and measured wrong on 2026-09-03.** Zero composables in the module are unskippable; `Active` is already reported stable; and strong skipping (Kotlin 2.4) skips equal-but-new instances for *unstable* parameters too, verified against a class with a public `var`. Nothing taking `table:` was failing to skip, so this was not the amplifier under 3, 5 and 7 — those were three independent bugs. See ENG-60 in `docs/todo.md`. |

## What was killed, and why it's recorded

Kept so nobody re-reports them. Each was a plausible, well-argued finding that did not survive.

- **Match-over dialogs stacking** (`PlayPokerScreen.kt:679`). The Kotlin claim was right (`null == false` is `false`), the causal claim wasn't: if the local player won, all three dialog branches are skipped anyway; if they busted, the guard was already false. The `takeIf` causes nothing.
- **Leave-confirmation misses subsidized tables** (`PlayPokerContract.kt:223`). The MP-31 degraded case does *not* slip through — with a non-`Active` table, `active?.practiceTierBotsOnly` is null and `null != true` is true. That is exactly why the author wrote `!= true` rather than `== false`.
- **Reward overlay completing early** (`HandRewardParticleOverlay.kt:104`). Unreachable: both anchors publish on the table's first layout pass, tens of seconds before any hand ends, and never null back.
- **Action-sheet preset desync** (`PlayerActionSheet.kt:74`). Unreachable — the subtree is discarded on every turn change, and even if hit, the submitted value is the one on screen.
- **`Text`'s uncached Regex** (`HtmlTextExt.kt:44`). Micro-optimisation theatre. ~0.1-0.3ms across every Text on screen, 1-2% of a frame, against glyph rasterization costing hundreds of µs *per changed string*. The finding also misread why `@NonRestartableComposable` matters: `BasicText` skips anyway on structural equality.

Downgraded rather than killed: the MP achievement clear (real, but a brief flicker on bots-only
practice tables, not the claimed cross-hand leak), the dead "Next hand" tap (real; the "permanent
wedge" needs a local SQLite read to hang), opponent auto-scroll jitter (6+ seats and a tight
window), the holographic card back (a **purchased** cosmetic, zero cost for anyone who hasn't
equipped it), and the between-hands countdown bar (one 6dp box relayout; the text doesn't change).

## The gap all four passes left

`docs/plans/renderthread-text-stall.md` records `HoleCardSlot` as the top remaining **measured**
recomposer at ~298, and names text drawn under animated scale/rotation as the leading unconfirmed
suspect. Nothing in this review touches either.

If there is a day for performance work, it belongs on confirming that with a Perfetto trace — not
on items 1-8 above. Everything here is inferred from reading; that one is measured.
