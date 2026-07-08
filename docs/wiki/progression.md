# Progression — XP, levels, achievements, celebrations

The progression layer is offline-first by design. The client computes XP / level / achievement progress locally and reconciles against the server when online. The server keeps authoritative `total_xp` so it can validate level-crossings without re-running gameplay.

## XP

- XP is awarded **per engagement intensity**, not per outcome — folding pre-flop earns less than running a hand to showdown. Decoupled from win / loss so a losing-but-engaged session still feels like progress.
- The XP-per-level curve lives in app-config (under `progression.*`) and is decoded into a Kotlin model by `:libraries:config`'s `JsonConfigValue` extension. The client ships a bundled default so it works offline. Editing the curve on the server doesn't require a release.
- `total_xp` is server-reconciled (Phase 3 Slice 1). `level` is still client-derived from `total_xp` via `levelProgressFor(...)` in `Level.kt`.

## Levels and level-up rewards

The level → reward table is also server-tunable (same `progression.*` config tree). Each level can grant chips, cosmetics, or consumables (XP Boost, Pick-a-Card chest).

**Chip rewards are server-minted (ENG-9).** The client still detects the trigger offline and applies an optimistic local credit for instant UI, but that credit is display-only until reconcile — the wallet ledger only ever takes the server's number. The server mints level chips on `POST /v1/me/progression/sync` when the authoritative XP total crosses a rewarded level, and achievement chips on `POST /v1/me/achievements/sync` when the earned id is recorded, under idempotent ledger keys (`levelup:<n>` / `achievement:<id>`) from server-owned amount tables (`RewardChips` in the server domain — its KDoc has the full story). A client-asserted wallet-sync credit whose reason starts with `levelup.` / `achievement.` is refused outright (`RefusedServerOwned` — no ledger write); the client drops the refused row and converges on the authoritative balance. The server tables mirror the client's bundled reward tables by hand; if they drift, the server's number wins on the next balance overwrite, so drift is a transient display blip, never a mint.

Non-chip rewards keep the offline-first client grant path:

| Reward type | Grant path |
|---|---|
| Chips | Server-minted at progression / achievements sync (`levelup:<n>` / `achievement:<id>` ledger keys); the client's local credit is optimistic display only |
| Cosmetic | Allowlisted server grant endpoint (`GrantsRoutes`), then inventory re-sync |
| XP Boost | Stash grant via `XpBoostRepository.grant()`; inactive until the player lights it from their profile |
| Pick-a-Card chest | Inventory grant with consumable quantity (when chest ships) |

## Achievements

> **Progress is server-authoritative (PROG-1, done).** Progress used to live only on the device, so it reset on reinstall / account-switch. The client now reports the raw facts of each hand and the server folds them into every counter; the bars read that server projection. Definitions + unlock/reward stay client-side by design. See **[achievements.md](achievements.md)** for the as-built system + the deliberate scoping decisions.

Achievements live in `libraries/cards/Achievement.kt`. Criteria are modeled as a sealed class — per-hand counters, custom cross-hand counters, etc. The engine evaluator picks them up automatically.

### V1 sharp edges (known and intentionally shipped)

- **Per-bot wins counter is liberally credited.** In a 4-seat bot table, a winning hand credits +1 to `wins_vs_bot_<name>` for *every* bot at the table, not just heads-up. The natural read of "Beat Jane 10 times" matches the liberal credit; tighten only when per-pot attribution becomes first-class (likely alongside multiplayer Elo tracking).
- **Mid-multiplayer-tournament criteria not modeled.** When MP tournaments ship, new `Criterion` subtypes will be needed for tournament-specific events (final-table appearance, bubble survival, heads-up wins). The evaluator picks new subtypes up automatically.
- **Achievement toasts only fire at hand-end.** All V1 criteria are hand-end-triggered, so unlocks live inside the showdown / bust dialogs. If a future criterion fires mid-hand, a separate on-table toast surface will be needed.

## Level-up celebration

A full-screen teal `RotatingDial` burst + the new level number + a warm line + Continue. Two load-bearing rules:

- **It only appears on Home, never at the poker table.** No mid-game takeover, no risk of stacking on the at-table achievement celebration.
- **It's a routed full-screen destination** (`LevelUpRoute`, modeled on `BlockingErrorScreen`), not an overlay. No bottom bar; back is swallowed; the only exit is Continue.

### The watermark trigger

The trigger is **derived from a persisted watermark** (`AppData.lastCelebratedLevel`), not from a table-side event:

1. Home composes / foregrounds.
2. Compare current level (from `levelProgressFor(progression.totalXp)`) against `lastCelebratedLevel`.
3. If current > watermark, navigate to `LevelUpRoute(level = current)` **and advance the watermark to current at navigate time** (not on Continue).

Advancing at navigate time keeps the navigation idempotent: when Home resumes behind the celebration there's nothing left to re-fire. Multi-level jumps collapse to one celebration because the trigger is *latest level* state, not a per-cross event.

**Trade-off (accepted):** a process death *while the celebration is on screen* won't re-show it next launch. The reward has already been granted by `LevelUpRewardGranter`, so the worst case is missing the reveal animation. Matches the welcome-dialog precedent.

### Seeding (existing users)

On first run after this shipped, the watermark was unset — so it seeded to the current level **without showing**. Existing users weren't blasted with celebrations for levels they reached pre-feature.

### Coordination with other surfaces

- **At-table achievement celebration stays in place.** It's contextual to the hand; the level-up is a Home moment. Different surfaces, no shared queue needed.
- **Server-scheduled dialogs** (`InAppMessageManager`) wait for the next foreground if the level-up is showing. Server dialogs are 1-per-foreground and yield.

## Key files

- XP / level math: `libraries/cards/.../Level.kt`, `LevelProgressGradient`.
- Achievements: `libraries/cards/.../Achievement.kt`, `PlayBotsState.recentlyEarned`.
- Celebration: `features/.../LevelUpRoute.kt`, `LevelUpRewardGranter.kt`.
- Watermark: `AppData.lastCelebratedLevel`.
- Sync: `ProgressionRepositoryImpl.sync`.
