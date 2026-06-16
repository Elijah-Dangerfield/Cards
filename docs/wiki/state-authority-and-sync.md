# State authority & sync — local, reconciled, or server-granted

How Cards decides **where a piece of state lives** and **who's authoritative**
over it. This is load-bearing because Cards is **offline-first**: a player can
open the app with no network, play bots all day, earn XP and achievements, and
spend/win chips — none of that can block on a round-trip. So "just make the
server authoritative" is not a free default; it has a real offline cost.

There are three models. Pick per piece of state, not globally.

> Companion: the read-side counterpart (caching reference data, when to hit the
> network on reads) lives in [`AGENTS.md` → "Repository read-path caching"](../../AGENTS.md).
> [`chip-grants.md`](./chip-grants.md) is a worked example of model 2 + 3.

---

## 1. Client-local

Computed and stored on the device; the server doesn't know it exists.

- **Examples (today):** achievement progress *counters* (`AchievementRepositoryImpl`) and lifetime hand counters (the `progression` singleton). Bots earn these fully offline; they don't leave the device yet. (XP/level and the achievement *earned set* both *graduated* to model 2 — see below.)
- **Use when:** the value is derivable on-device, doesn't need cross-device truth yet, and abuse doesn't matter (low/zero stakes).
- **Cost:** no cross-device consistency, no server-side anti-abuse. Fine until the value gains real stakes — then it graduates to model 2 or 3 (the V1 progression/achievement schemas are deliberately shaped to mirror the eventual server tables so that migration is a one-shot import).

## 2. Optimistic-local + server-reconciled  ← the default for *valuable* state that must work offline

Apply the change **immediately on-device** with an **idempotency key**, enqueue
an event, and let the server **reconcile on the next sync**. The local store is
the source of truth *between* syncs; the server is authoritative-of-record.

- **Examples (today):** chips (`ChipsRepositoryImpl` — local balance + `wallet_events` ledger, flushed to `/v1/me/wallet/sync`), inventory + equipped cosmetics after a purchase, **XP / level** (`ProgressionRepositoryImpl` — local total + `xp_events` ledger flushed to `/v1/me/progression/sync`; `level` derived from the reconciled total), and the **achievement earned set** (`AchievementRepositoryImpl` — earned rows flushed to `/v1/me/achievements/sync`; criteria + progress counters stay local).
- **Grant locally, reconcile backend.** This is the pattern to reach for when something of value is earned during offline play. The user gets it instantly; the server confirms/corrects later. If the server rejects an event, the local balance is reset to the server's authoritative value on sync.
- **Use when:** it must work offline *and* the value matters enough to want a server record (cross-device, audit, eventual anti-abuse).

## 3. Server-authoritative grant

The **server decides and issues**; the client displays. The client cannot
proceed correctly without the server's answer.

- **Examples (today):** the starter chip grant (server-issued on first `/v1/me`), the server-side cosmetic-reward mapping that grants an item into inventory when the client reports an earned achievement.
- **Canonical example (planned):** a **reward-chest / loot-box open** — the server rolls a weighted table and grants the prize; the client only animates the reveal. A roll is exactly the value the client must not compute (fairness + anti-reroll), so "open" requires connectivity even though the unopened chest is owned offline.
- **Not offline-friendly** — that's the whole tradeoff. Reserve it for:
  - values the client genuinely **can't or shouldn't compute** (a server-issued amount, anything secret/server-only),
  - cases where **trust or cross-device correctness outweighs offline UX**.
- **Always pair with graceful degradation.** Try the server, wait a short grace window, and if it doesn't land, show "lands when you reconnect" and leave a flag to reveal it later. The onboarding starter-grant reveal is the canonical implementation (see [`chip-grants.md`](./chip-grants.md)); don't block the UI on the grant.

---

## Cross-cutting rules (apply to any grant)

- **Idempotency is mandatory.** Key every grant on its natural identity — wallet-event key, achievement id, `levelup_<level>`. This is what protects against retries, reinstalls, multi-level jumps, and "tell the server twice," *independent of* whether anyone is cheating. It's the one rule you never skip.
- **The server validates; it doesn't trust the claim — and how hard it validates scales with the stakes.** A "client grants, then POSTs that it granted" endpoint is spoofable: an authenticated user can claim anything. That's *acceptable* for play-money / free cosmetics (the cheater mostly cheats themselves). It stops being acceptable when a prize is **IAP-equivalent** (real revenue) or tied to **ranked / leaderboard status**. To harden, the server **derives** entitlements from synced facts (XP / hands) rather than accepting a "give me prize X" command, and applies sanity caps + rate limits + reconcile/claw-back. (This is the Duolingo model: grant optimistically offline, recompute + cap on sync.)
- **Separate the grant from the celebration.** The grant is data (apply it the moment it's earned, idempotently). The celebration is UI (can be deferred — e.g. the level-up screen shows on Home). Never gate the value behind seeing an animation.
- **Static reward tables are content, not network.** A fixed mapping the client already knows (XP formula, achievement rewards, a level→prize table) ships in the app and works offline — no pre-fetch. Only fetch a reward schedule if it's *server-tunable* (then it's remote-config via `:libraries:config`, model 1-with-refresh).

---

## Choosing a model

```
Does the client need to act on this while offline?
├─ No  → Model 3 (server-authoritative grant) + graceful-degrade.
└─ Yes → Does it have real value (money / ranked status / cross-device)?
         ├─ No  → Model 1 (client-local).
         └─ Yes → Model 2 (optimistic-local + server-reconciled), idempotent.
                  Harden server-side validation as the stakes rise.
```

| State | Model today | Authority | Offline |
|---|---|---|---|
| XP / level | 2 — optimistic + reconcile | server-of-record | ✅ earns offline |
| Achievement earned set | 2 — optimistic + reconcile | server-of-record | ✅ earns offline |
| Lifetime hand counters | 1 — client-local | client | ✅ earns offline |
| Achievement progress counters | 1 — client-local | client | ✅ earns offline |
| Achievement → cosmetic reward | 3 — server grant (after client notify) | server | queued, grants on sync |
| Chips wallet | 2 — optimistic + reconcile | server-of-record | ✅ applies offline |
| Inventory / equipped | 2 — optimistic + reconcile | server-of-record | ✅ applies offline |
| Starter chip grant | 3 — server-issued | server | degrades gracefully |

**Worked example — level-up prizes:** XP/level is now model 2 (`total_xp`
server-reconciled; `level` derived from it). A level-up prize still rides the
existing path for its type (chips → model 2 wallet ledger, key `levelup_<level>`;
cosmetic → the achievement-reward path), granted on level-cross and celebrated
later on Home. Compute the level-cross against the **reconciled** total so a
cross-device multi-level jump grants each level once. Client-grant + idempotent
now; server-derived when stakes rise. See `decisions.md` for the dated call.
