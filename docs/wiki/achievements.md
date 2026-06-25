# Achievements — how progress is kept

How achievement progress survives reinstall / account-switch (PROG-1), and the
deliberate decisions about what is and isn't server-driven. This describes the
system **as built**.

## The model: players report facts, not progress

The key idea — and the thing that makes everything else fall out — is that the
client reports the **raw facts of each finished hand** ("won, pot 1,200, all-in,
showed a flush, busted 1 opponent"), never per-achievement progress. The server
folds those facts into every achievement counter.

| Piece | Scope | Lives in |
|---|---|---|
| **Definitions** — the catalog: id, criterion, threshold, reward, display | Same for everyone | **Client code** (`AchievementRegistry`). *Deliberately not server-served — see below.* |
| **Facts** — the raw record of one finished hand | Per user, append-only | `player_stat_events` ledger; client outbox flushes them to `POST /v1/me/player-stats/sync` |
| **Progress (counters)** — derived from facts ("7/10 wins vs Jane") | Per user, derived | `AchievementCounters.fold` in shared `:libraries:achievements`; materialized on `user_player_stats.achievement_counters` (JSONB) |
| **Earned** — which achievements you've unlocked | Per user | Synced to the server (the existing achievements endpoint) |
| **Unlock + reward** — crossing the threshold → celebration + chips | — | **Client-side, optimistic** (the existing engine). *Not server-validated — see below.* |

Because the server derives counters from raw facts (never from a client-sent
progress number), a reinstall can't reset or clobber progress: the fresh client
sends no new facts for already-applied hands, so the server's counters stand.

And because the **facts** are stored append-only, a brand-new achievement
back-fills from a player's history the moment it's added — "player actions are
agnostic of achievements."

## Data flow (as built)

```
Each hand ─▶ client appends raw FACTS to the local outbox ─▶ batched flush
                                                                  │
                                          POST /v1/me/player-stats/sync
                                          server folds facts → counters,
                                          returns the authoritative snapshot
Stats / Achievements screen
   • earned badges   ← synced earned set (already cross-device)
   • progress bars   ← server counters (AchievementProgress.withServerCounters)
   • unlock + chips  ← local engine, optimistically, offline-instant
```

- **Progress bars** read the server's counter projection (overlaid onto the
  local base by `withServerCounters`), so they survive reinstall / account
  switch. The counters the server doesn't fold from hand facts keep their local
  value: `current_level` (XP-derived, and XP is separately server-reconciled),
  `tutorial_complete`, and the always-zero multiplayer keys.
- **The contract:** the server folds counters under the *same* string keys the
  client's `Criterion` uses (`no_bust_streak`, `max_pot_seen`, `wins_vs_bot_*`,
  …). Today that alignment is maintained by hand across the two modules; a future
  unification onto the shared module's keys would make it compiler-enforced.

## Two things we deliberately did NOT make server-driven

Both were considered and rejected for now (with reasons), so the system stays
simple. They live in the backlog if a concrete need appears.

### 1. Server-*served* achievement definitions (hot-add without a release)

Rejected. The client must ship a complete bundled catalog **anyway** for offline,
so server definitions would *override* the client copy, not replace it —
duplication, not simplification. And a genuinely new achievement almost always
needs a new icon/copy → an app release regardless. The only release-free wins
would be retuning a threshold/reward or a kill-switch, which are rare enough not
to justify a definitions endpoint + data-driven rendering now. The app-config
mechanism already exists, so this can be added cheaply later if live-ops needs it.

### 2. Server-authoritative unlock + reward granting

Deferred. The server *could* re-derive each crossing and grant the chips exactly
once (anti-cheat, exactly-once across devices) — it already does this for the
multiplayer achievements. But chips are freemium (no cash-out), so a cheated or
double-granted achievement reward is in-game inflation, not lost money. For that
stake it isn't worth moving the bot-mode grant off the existing client-optimistic
path right now.

## Known limitation (the cost of deferring #2)

Progress **display** is server-authoritative, but the client's **unlock engine**
still reads local counters that reset on reinstall. So immediately after a
reinstall, a player who was mid-progress and crosses a threshold may see the bar
at ~100% (server) while the unlock celebration lags until their *local* count
re-accumulates. The earned set is synced, so already-earned achievements never
re-fire or get lost — only an in-flight unlock can be delayed in that narrow
window. Closing this is the backlog item below (reconcile the local engine's
counters from the server snapshot, or move granting server-side).

→ Backlog: **server-authoritative achievement granting + local-counter reconciliation.**

## Implementation status (PROG-1 — complete for the reset bug)

- **Phase 1 — done.** Server event-sources raw `HandFacts`; `AchievementCounters.fold`
  (shared module) materializes every counter on `user_player_stats`; exposed on the
  read endpoint. Streak derived from a bust fact, never a client snapshot.
- **Phase 2a — done.** Client sends the full per-hand facts so the rich counters populate.
- **Phase 2b — done.** Progress bars render from the server counters
  (`withServerCounters`), surviving reinstall / account switch.
- **Phase 3 — not doing now (backlog).** Server-served definitions and
  server-validated granting, per the two decisions above.
