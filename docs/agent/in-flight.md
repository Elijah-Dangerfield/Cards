# In-flight

## fix: attribute MP hand outcomes to the local seat, not seat 0

**Problem:** Folding a multiplayer hand still credited `SHOW_*` achievements ("show a straight at showdown") even though the player never reached showdown.
**Approach:** Root cause was `PlayPokerViewModel.humanSeatIndex` hard-coded to `0`. Solo always seats the human at 0, but MP allocates any seat — so every per-hand attribution (XP, achievements, win-odds equity) was computed against seat 0's outcome. A folded human at seat 1 inherited seat 0's showdown hand. Added `PokerSessionFactory.humanSeatIndex(state)` (solo: fixed seat; remote: match `localUserId`, `-1` when not seated so a missing match yields no credit instead of crediting the wrong seat) and resolved the real seat at the two attribution sites (`handleHandEnded`, win-odds equity). Also added the belt-and-suspenders `!summary.wasFold` guard on the `Criterion.ShowAtLeast` branch per the todo.
**Reviewer notes:** This also silently fixed the win-odds tool for non-seat-0 MP humans (it was reading seat 0's scrubbed/empty hole cards and giving up). The `-1`-when-not-seated contract is the safe default for spectators. Builder test simulates an over-sharing reveal map (folded human's cards present) to prove `wasFold` still suppresses credit.

## feat: first-multiplayer-hand achievement (FIRST_HAND_MP)

**Problem:** No welcoming first-milestone before the `HANDS_100_MP` grind — finishing your first MP hand earned nothing.
**Approach:** New count-based server-witnessed id riding the existing `DefaultServerWitnessedAchievements` path: added `FIRST_HAND_MP` at threshold `1` in `COUNT_THRESHOLDS`, the client achievement definition ("Took a seat", COMMON, 🤝, MULTIPLAYER mode), and the `serverWitnessed` deny-list entry so the client grant route 403s it. Record-only — no `REWARD_PRODUCTS` entry, so no cosmetic/chips, per the todo.
**Reviewer notes:** Picked name "Took a seat" / icon 🤝 — directional copy call; reviewer can rename. Adding the threshold-1 id meant the count-based server tests now fire two achievements at count 100 and one at count 1, so I retargeted those tests (the "below threshold grants nothing" case moved to count 0) and added a dedicated count-1 test.

## feat: HANDS_100_MP grants chips, not a borrowed emote

**Problem:** The 100-finished-MP-hands achievement handed out the single-player `emotes_grinder` emote pack as a stand-in — a borrowed cosmetic, not a real MP reward.
**Approach:** Added `Wallet.ACHIEVEMENT_HANDS_100_GRANT = 2_500`, dropped `HANDS_100_MP` from `DefaultServerWitnessedAchievements.REWARD_PRODUCTS` (now empty, kept as the cosmetic seam), and added a `CHIP_REWARDS` map applied via `wallet.apply(idempotencyKey="achievement:HANDS_100_MP", delta=2500, reason="achievement_grant:HANDS_100_MP")`. The stable ledger key makes the re-evaluation-every-hand path idempotent. Injected `WalletRepository` into the evaluator.
**Reviewer notes:** `REWARD_PRODUCTS` is intentionally empty now but retained as the generic cosmetic seam for future count achievements (the catalog read path is dead until something maps there again — could be inlined if you'd rather not carry it). 2,500 chips is the todo's specified amount.

## fix: drop equipped title from the per-seat player area

**Problem:** The human's equipped title rendered as a gold "You · The Shark" suffix in the cramped seat name area; it belongs only on the tapped Player Card.
**Approach:** Removed the title rendering from `PlayerInfoTile` and ripped out the whole now-dead `equippedTitle` path rather than just blanking it — the seat suffix was its only consumer (the Player Card resolves its title independently via the `badges`/`resolvePlayerBadges` path, untouched). Removed: `PlayPokerState.equippedTitle`, `EquippedTitleChanged` action + handler, the `titleForProductId` computation in the equipment observer, and the `humanTitle`/`title` params threaded through `ActiveTable` → `PlayerArea` → `FlippablePlayerInfoTile` → `PlayerInfoTile`.
**Reviewer notes:** Verified the Player Card title is unaffected — it comes from `EquippedBadgesChanged`/`resolvePlayerBadges`, a separate observer. `titleForProductId` (in `:libraries:ui`) is now unused by room but stays as a DS function. Couldn't eyeball the rendered seat in Studio (headless), but the change is a pure deletion of the suffix branch.

## feat: orphan-anon sweep preserves accounts above level 1

**Problem:** The L1 install-id orphan sweep (`DefaultOrphanInstallSweep`) could delete a superseded anon account that had earned real XP — only inventory / live-room / IAP gates protected it.
**Approach:** Injected the server `ProgressionRepository` and added an XP guard to `verifyCandidate`: a candidate with `total_xp >= 100` (level 2+ on the client `N²×100` curve) is preserved. A missing progression row reads as 0 XP (level 1, still deletable). The 100-XP threshold is a self-contained constant with a comment pointing at `Level.kt` — the server intentionally doesn't depend on `:libraries:cards`, matching its independence from the client/Compose layer.
**Reviewer notes:** Threshold duplicated rather than imported — flagging in case you'd prefer the server take a `:libraries:cards` dep for the canonical curve, but that pulls the client module into the JVM server for a one-line constant. Policy/rationale was already logged in `decisions.md` 2026-06-19, so no new decision entry. The ≥1-year-inactivity deletion branch stays deferred (post-launch.md).
