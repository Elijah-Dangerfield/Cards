# TODO

**Last reviewed:** 2026-07-01 (todo-maintainer) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

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

## ENG — Engineering / structural

- `[P2]` **Add `@Preview` to the two lobby screens. (proposed 2026-07-01)** `PrivateCreateScreen.kt` and `PrivateJoinScreen.kt` in `:features:lobby:impl` are stateless `Screen(state/args, onAction)` composables with no `@Preview`, unlike their siblings (`LobbyScreen` has an in-room content block; most feature screens carry previews).
  **Acceptance:** each screen has ≥1 `@Preview` rendering a representative state through `PreviewContent { ... }`, matching the `ShopScreen` / `StatsScreen` shape.
  **Hints:** copy the `ShopScreenPreview_*` pattern (stateless screen + fabricated state). `LobbyScreen` itself is bigger — file separately if it grows the diff.

- `[P2]` **Add `@Preview` to the two public-matchmaking screens. (proposed 2026-07-01)** `PublicFindScreen.kt` and `PublicSearchingScreen.kt` in `:features:rooms:impl` are stateless screen composables (searching / choosing / joined / bot-fallback content states) with no `@Preview`.
  **Acceptance:** each screen has `@Preview`s covering its main states (e.g. searching and choosing) via `PreviewContent { ... }`.
  **Hints:** `PublicSearchingScreen`'s `*Content` blocks map cleanly to one preview each; mirror the `StatsScreen` preview convention.

- `[P2]` **Add `@Preview` to `ProfileScreen`. (proposed 2026-07-01)** `ProfileScreen.kt` in `:features:profile:impl` is a stateless composable taking `settings` / `achievementProgress` / `ownedItems` / `playStyle`, but has no `@Preview` while smaller sibling screens in the same module (Settings, EditProfile, ClaimAccount, …) do.
  **Acceptance:** ≥1 `@Preview` renders `ProfileScreen` with fabricated state through `PreviewContent { ... }`.
  **Hints:** follow the module's existing preview shape; a populated + an empty-inventory variant is enough.

---
