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

- `[P2]` **ENG-17 — Migrate `apps/admin` off `runCatching`.** (proposed 2026-07-04) The config-admin web app's production code uses `runCatching` in ~9 places (`AdminApi.kt`, `Main.kt`, `Support.kt`, `AuditView.kt`), against the repo-wide `Catching` convention — `runCatching` swallows `CancellationException`.
  **Acceptance:** no `runCatching` in `apps/admin` production sources; cancellation still propagates.
  **Hints:** `:libraries:core` has no `js()` target, so either add one or drop a local `Catching` equivalent into `apps/admin`.

- `[P2]` **ENG-18 — Resolve the AvatarPackCache sign-out-clear promise.** (proposed 2026-07-04) `AvatarPackCache`'s KDoc says the cache is "cleared on sign-out (handled by ProfileRepositoryImpl's SignedOut listener once that gets wired — see the TODO there)", but no SignedOut listener or TODO exists in `ProfileRepositoryImpl`; the only `clear()` call is the staleness drop. Wire the sign-out clear or correct the doc — the catalog is user-agnostic per the same KDoc, so the doc fix is likely right.
  **Acceptance:** the KDoc matches reality — no reference to unwired listeners or nonexistent TODOs.
  **Hints:** `libraries/identity/impl/.../profile/AvatarPackCache.kt:26-27`, `ProfileRepositoryImpl.kt:550`. While there, kill the stale "(mock)" in `features/rooms/impl/.../PublicFindScreen.kt:55` — matchmaking is real now.
