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
                  │                                              │
   EFFECTIVE COUNTERS = server snapshot              POST /v1/me/player-stats/sync
   folded with the unsynced outbox                   server folds facts → counters,
   (shared AchievementCounters.fold)                 returns the authoritative snapshot
        │                    │
   progress bars        unlock + chips  ← one source, optimistic, offline-instant
   earned badges        ← synced earned set (already cross-device)
```

- **One source — the effective counters.** `PlayerStatsRepository.observeEffectiveCounters`
  folds the unsynced outbox facts onto the cached server snapshot with the *same*
  `AchievementCounters.fold` the server runs. Both the progress bars and the
  unlock engine read it (`achievementProgressFrom`), so they always agree, work
  offline (server-last-known + this session's hands), and survive reinstall (a
  fresh client folds an empty outbox onto the hydrated snapshot → exact server
  truth). The old device-only counter table is gone.
- Counters the server doesn't fold from hand facts are supplied at read time:
  `current_level` (XP-derived, XP is separately server-reconciled) and the
  bot-whisperer capstone (derived from the per-bot win family). Tutorial + MP
  achievements have no fact counter, so their bars read 0 until earned — correct,
  since they're tracked by the earned set / granted server-side.
- **The contract:** the server folds counters under the *same* string keys the
  client's `Criterion` uses (`no_bust_streak`, `max_pot_seen`, `wins_vs_bot_*`,
  …). Today that alignment is maintained by hand across the two modules; moving
  the registry's keys onto the shared module would make it compiler-enforced.

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

## Implementation status (PROG-1 — done)

- **Phase 1 — done.** Server event-sources raw `HandFacts`; `AchievementCounters.fold`
  (shared module) materializes every counter on `user_player_stats`; exposed on the
  read endpoint. Streak derived from a bust fact, never a client snapshot.
- **Phase 2a — done.** Client sends the full per-hand facts so the rich counters populate.
- **Phase 2b — done.** Progress bars render from the server counters.
- **Unification — done.** Both display and unlock read one effective-counter
  source (server snapshot + unsynced outbox, shared fold); the legacy device-only
  counter table is retired. So the unlock engine is server-truthful too — after a
  reinstall a mid-progress achievement unlocks correctly off server history, not a
  reset local tally. Progress, celebration, and earning all work offline and are
  fully retained across reinstall / account switch.

The one open question, **not** part of fixing the reset bug, is parked in the
backlog: whether achievement **definitions** should eventually be backend-driven
(hot-add without an app release) rather than hardcoded in the client. Today
they're client-side, which is the right default (the client needs a bundled
catalog for offline anyway, and new achievements usually ship with assets).
