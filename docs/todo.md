# TODO

**Last reviewed:** 2026-07-04 (todo-maintainer) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

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

## ENG

- `[P2]` **ENG-24 — Add `@Preview` coverage to the app-guard blocking overlays.** (proposed 2026-07-04) `features/upgrade/impl/.../AppGuardLayer.kt` renders the upgrade-required and maintenance-blocking full-screen overlays plus the maintenance banner, and the module has zero previews — these surfaces are invisible until a forced upgrade, so previews are the only cheap way to see them.
  **Acceptance:** previews via `PreviewContent` cover UpgradeRequired, MaintenanceBlocking (with a message), and MaintenanceBanner.

## ROOM

- `[P1]` **ROOM-16 — Add `@Preview` coverage to the public-matchmaking screens.** (proposed 2026-07-04) `:features:rooms:impl` has zero previews; `PublicFindScreen` and `PublicSearchingScreen` both already take raw inputs, and the searching screen has distinct phases (searching / bot-offer / choosing with candidate cards / joining-bots) previews would pin.
  **Acceptance:** previews via `PreviewContent` cover `PublicFindScreen` (normal + wallet-capped range) and each `PublicSearchingScreen` phase.


