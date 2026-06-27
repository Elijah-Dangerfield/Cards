# TODO

**Last reviewed:** 2026-06-27 · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

The live punch list of actionable engineering work. Every item is something a worker can pick up and ship.

**Build the best thing, not the smallest change.** This is a greenfield, unshipped codebase — the goal is scalable, maintainable, production-ready systems. When you pick an item up, take a step back and ask what's genuinely best for the project and the user, then build that, even if it means restructuring or rebuilding something already here. Don't stack a minimal patch on top of what exists. Full rule: AGENTS.md → Coding Guidelines and [`docs/agent/worker-prompt.md`](agent/worker-prompt.md) → Picking work.

**Fixing a bug? Reproduce it with a failing test first.** Red (the test fails *because of the bug*), then green (the fix makes it pass). It proves you found the real cause, not a guess, and leaves a permanent regression guard. Can't reproduce it in a test? The harness is missing something — build that first. See AGENTS.md → Coding Guidelines.

**Minimum viable context.** Every item is one bold title + at most ~3 short lines (Problem / Acceptance / Hints). No status archaeology — don't narrate what already shipped, what's "locked," or which sub-part landed when. If a sub-part ships, delete that clause. A bullet a human can't skim in five seconds is too long.

**Item IDs.** Every item carries a stable ID — section prefix + number (`PROG-1`, `AUTH-3`, `MP-2`). IDs never get reused: when an item ships or moves to backlog, its number retires with it. Use the ID when referring to an item in commits, PRs, or other docs.

**ID prefixes:** `PROG` (progression / XP / stats), `AUTH` (auth + onboarding), `GAME` (gameplay + table UX), `SHOP` (consumables + rewards), `SOC` (social graph), `ROOM` (rooms UI), `MP` (multiplayer hardening), `ENG` (engineering / structural), `BILL` (billing).

**Priority tags** (every item carries one; bias toward P0 first):

- `[P0]` — V1 ship-blocker or load-bearing for other work.
- `[P1]` — Real value, not blocking.
- `[P2]` — Lower urgency, still worker-pickable. Many need a directional call — make a recommendation, ship a slice, let the reviewer course-correct.

Everything here is worker-pickable. Human-only work (device QA, dashboard config, content, product decisions) lives in [`developer-todo.md`](./developer-todo.md). Deferred ideas live in [`backlog.md`](./backlog.md) — when an item gets descoped or doesn't fit V1, move it there, don't delete it.

---

## B. Auth & onboarding

_No open engineering items. (AUTH-9 — the Google browser-OAuth redesign to suspend-until-redirect, with link ≠ sign-in — shipped 2026-06-27, see [decisions.md](./decisions.md). End-to-end device QA + the Supabase dashboard config it depends on remain in [developer-todo.md](./developer-todo.md).)_

_Other follow-ups live in [developer-todo.md](./developer-todo.md); deferred ideas in [backlog.md](./backlog.md)._

---

## F. Shop & cosmetics

- `[P2]` **SHOP-3 — Host-chosen felt + card backs, shown to every player at the table.** Owner directive: let the game creator pick the felt and card backs from their inventory when creating a room, and have *every* player at the table see the host's chosen felt and card backs (incentivizes buying cosmetics). The host's selection already exists per-player; this makes it table-wide.
  **Acceptance:** create-room flow lets the host pick an owned felt + card back; the room snapshot carries them; all clients render the host's felt and card backs in-game. Ship a slice + a directional call on edge cases (host has none equipped → table default; whether a player's own equipped back still applies to their own cards) and let the reviewer course-correct.
  **Hints:** plumbing mirrors the "Player Card — Phase 2: opponent cosmetics over the wire" backlog item — put the host's equipped felt/back on the room/seat snapshot and read it at the play surface instead of `LocalCurrentFelt`/local equip. Owner directive, Sentry [CARDS-4Q](https://elijah-dangerfield.sentry.io/issues/CARDS-4Q).

---

## H. Engineering & structural

- `[P1]` **ENG-6 — Verify the force-update gate reliably covers an already-in-game client.** The cross-version rule (decisions.md 2026-06-27, CARDS-4S) relies on raising `upgrade.minSupportedVersionCode` to lock out clients too old to parse a breaking game-object change. `AppGuardState.from` already evaluates the *streamed* config map and `AppGuardLayer` renders an app-wide `UpgradeRequired` overlay, so it should take effect live — but this hasn't been verified for a client mid-hand.
  **Acceptance:** a test (or documented manual check) confirms that bumping `minSupportedVersionCode` in streamed config raises the blocking overlay over the play screen for an in-session client within the config refresh window — not only on next cold boot. If the refresh cadence or overlay z-order leaves a gap, close it.
  **Hints:** [AppGuard.kt](features/upgrade/src/commonMain/kotlin/com/cards/features/upgrade/AppGuard.kt), [AppGuardLayer.kt](features/upgrade/impl/src/commonMain/kotlin/com/cards/features/upgrade/impl/AppGuardLayer.kt), config key `upgrade.minSupportedVersionCode`; check the `AppConfigMap` stream refresh cadence (push vs poll) and that the overlay sits above the nav graph including the play surface.

- `[P1]` **ENG-7 — Catch game-deserialization failures and show a graceful "update may help" message.** Defense-in-depth beneath the cross-version rule (decisions.md 2026-06-27, CARDS-4S): if a game/state object ever fails to deserialize — a breaking change slipped past the additive-only convention, or a client is mid-flight during a rollout — the client must not crash or hang. Surface a clear message and let the user back out.
  **Acceptance:** with game-state decoding wrapped in a `Catching` block, a malformed / newer-shaped payload yields the message ("We're struggling to play this game. It may have been created with a newer app version — updating may help.") plus a safe exit (back to lobby/home), not a crash or stuck screen. A failing test feeds a deliberately-undeserializable payload and asserts the fallback. Point the user at the store update where possible.
  **Hints:** the game-state decode path in `RemotePokerSession` / the room-socket frame parsing; use `Catching {}` (repo convention, never `runCatching`); reuse the blocking-screen patterns in `:features:upgrade` for the message surface. Pairs with ENG-6.
