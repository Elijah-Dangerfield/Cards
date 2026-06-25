# Achievements — server-authoritative, offline-instant

The design for making achievement progress survive reinstall / account-switch
(PROG-1) without making the unlock feel laggy or break offline. This is the
reference for the in-flight implementation.

## The five pieces (don't conflate them)

| Piece | Scope | Lives in |
|---|---|---|
| **Definitions** — the catalog: id, which counter, threshold, reward, display | Same for everyone | Bundled default in `:libraries:achievements`; live catalog server-served (overrides + hot-adds) |
| **Facts** — the raw record of one finished hand ("won, pot 1,200, all-in, showed a flush") | Per user, append-only | `player_stat_events` ledger; client outbox flushes them |
| **Progress** — counters derived from facts ("7/10 wins vs Jane") | Per user, derived | `AchievementCounters.fold` (shared); materialized on `user_player_stats.achievement_counters` |
| **Earned / claimed** — which achievements you've unlocked + been paid for | Per user | Server-authoritative; the existing achievements endpoint |
| **The unlock moment** — crossing the threshold → celebration + reward | — | *See the core decision below* |

The headline win is that **player actions are agnostic of achievements**: the
client reports raw facts, never per-achievement progress. So a brand-new
achievement back-fills from a player's stored history the moment it's added — no
"only counts from today" asterisk.

## The core decision: who decides you crossed the line?

The client **celebrates optimistically**; the server is the **authority of
record + reward**.

- The client ships the definitions and runs the *same* shared fold + threshold
  check, so it knows the instant you cross — and fires the celebration
  immediately, **even offline**.
- The server independently re-derives the crossing from the raw facts on sync,
  owns the durable `earned`/`claimed` record, and grants the reward **exactly
  once** (keyed wallet event).

**Why optimism is safe:** the celebration doesn't have to be *trusted*. A hacked
client could fake the popup — but the server gates the actual chips, so faking
the animation earns nothing. We get the instant dopamine without giving up
integrity. (Rejected alternative: a thin client where the server decides
everything and the client GETs to display. Simpler and un-cheatable, but the
unlock isn't visible until the next flush→GET round trip — and offline bot play
sees nothing until reconnect. Not acceptable for a reward feature on an
offline-first app.)

## Data flow

```
Each hand ─▶ client appends raw FACTS to local outbox ─▶ batched flush
                  │                                            │
   (offline) client folds facts → counters → checks      POST /v1/me/player-stats/sync
   definitions → CELEBRATES instantly                    server folds facts, records
                                                          EARNED, grants REWARD once,
                                                          response says "you just
                                                          earned X (+chips)"
Achievements screen ─▶ GET → catalog + your progress + earned/claimed
                            (source of truth; reconciles optimism; how a
                             reinstall / 2nd device gets it right)
```

Two properties make this clean:

- **The flush response carries the unlock.** `POST /sync` already returns your
  updated counters; it also returns newly-earned achievements + rewards granted.
  So online, the flush *is* the get — one round trip, no flush-then-GET race.
- **The GET is the reconciliation surface**, not the dopamine path. It returns
  the catalog + per-user progress + earned/claimed for the achievements screen,
  for reconciling a brief optimism divergence, and for a fresh device with no
  local history. The client never depends on it to celebrate.

## Definitions: server-served, bundled default

Endpoint vs config-blob is just packaging; the invariant is that the **client
ships a bundled default catalog**, because offline / first-launch-no-network it
needs something to show and evaluate against.

- `:libraries:achievements` holds the definition **shape**, the **bundled
  default**, and the **fold** — the one module both client and server compile.
- The server **serves** the live catalog (overrides + hot-added achievements)
  and **evaluates** the same catalog → no drift between "what it told the
  client" and "what it grants."
- Hot-adding an achievement requires no app release **if cards render from
  data** (data-driven title + an icon the client can resolve without a new
  asset — emoji or a named-icon set with an "unknown" fallback). Lock that in
  early or hot-add silently regresses to needing a release.

## What stays where

- **Definitions** → shared code + server catalog (same for everyone).
- **Earned / claimed state** → per-user achievements endpoint (yours).
- **Counters** → `user_player_stats.achievement_counters`, derived server-side.
- **Level / XP** → unchanged; its own `progression.*` config (see
  [progression.md](progression.md)).

## The one trade we accept

The client carries the definitions + a threshold check (cheap — shared module),
and we handle the rare "client celebrated, server says already-earned-elsewhere"
by reconciling from the GET (don't double-celebrate). That's the entire
downside.

## Implementation status (PROG-1)

- **Phase 1 — done.** Server event-sources raw `HandFacts`; `AchievementCounters.fold`
  materializes every counter on `user_player_stats`; counters exposed on the read
  endpoint. Streak derived from a bust fact, never a client snapshot.
- **Phase 2a — done.** Client sends the full per-hand facts so the rich counters populate.
- **Phase 2b — in progress.** Point the progress bars at the server counter snapshot
  (local DAO becomes a cache, not the truth).
- **Phase 3 — planned.** Definitions as shared-shape + server-served catalog (bundled
  default); server-authoritative unlock + claim-once with optimistic client celebration;
  sync response carries newly-earned + reward.
