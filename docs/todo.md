# TODO

**Last reviewed:** 2026-07-21 (todo-maintainer) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

The live punch list of actionable engineering work. Every item is something a worker can pick up and ship.

**Build the best thing, not the smallest change.** This is a greenfield, unshipped codebase — the goal is scalable, maintainable, production-ready systems. When you pick an item up, take a step back and ask what's genuinely best for the project and the user, then build that, even if it means restructuring or rebuilding something already here. Don't stack a minimal patch on top of what exists. Full rule: AGENTS.md → Coding Guidelines and [`docs/agent/worker-prompt.md`](agent/worker-prompt.md) → Picking work.

**Fixing a bug? Reproduce it with a failing test first.** Red (the test fails *because of the bug*), then green (the fix makes it pass). It proves you found the real cause, not a guess, and leaves a permanent regression guard. Can't reproduce it in a test? The harness is missing something — build that first. See AGENTS.md → Coding Guidelines.

**Minimum viable context.** Every item is one bold title + at most ~3 short lines (Problem / Acceptance / Hints). No status archaeology — don't narrate what already shipped, what's "locked," or which sub-part landed when. If a sub-part ships, delete that clause. A bullet a human can't skim in five seconds is too long.

**Item IDs.** Every item carries a stable ID — section prefix + number (`PROG-1`, `AUTH-3`, `MP-2`). IDs never get reused: when an item ships or moves to backlog, its number retires with it. Use the ID when referring to an item in commits, PRs, or other docs.

**ID prefixes:** `PROG` (progression / XP / stats), `AUTH` (auth + onboarding), `GAME` (gameplay + table UX), `SHOP` (consumables + rewards), `SOC` (social graph), `ROOM` (rooms UI), `MP` (multiplayer hardening), `ENG` (engineering / structural), `BILL` (billing), `ECON` (chip economy integrity), `MOD` (trust & safety / moderation), `SITE` (marketing / support static pages).

**Priority tags** (every item carries one; bias toward P0 first):

- `[P0]` — V1 ship-blocker or load-bearing for other work.
- `[P1]` — Real value, not blocking.
- `[P2]` — Lower urgency, still worker-pickable. Many need a directional call — make a recommendation, ship a slice, let the reviewer course-correct.

Everything here is worker-pickable. Human-only work (device QA, dashboard config, content, product decisions) lives in [`developer-todo.md`](./developer-todo.md). Deferred ideas live in [`backlog.md`](./backlog.md) — when an item gets descoped or doesn't fit V1, move it there, don't delete it.

---

## ENG-35 [P1] — Banned client keeps firing (and Sentry-logging) doomed requests; no circuit breaker or un-ban recovery

**Problem:** A banned user's client keeps sending normal requests that all 403 `{"reason":"banned"}` (Sentry CARDS-BG), each logged to Sentry as an error. There's no local short-circuit, and no way out of the blocking `AccessDeniedScreen` without an app relaunch. Sibling to AUTH-29 (that's the missing-`auth.users` 500 path; this is the clean-403 banned path).

**Acceptance:** While banned, non-allowlisted requests are short-circuited client-side (zero wire traffic, no error telemetry); `/v1/me` stays allowlisted and a 200 clears the banned flag and dismisses the screen without relaunch. Expected 4xx (403 banned, 401) and user-cancellations no longer reach Sentry as errors.

**Hints:** Reuse the `ExpectedControlFlow` short-circuit pattern (auth). Full plan, file list, and tests: [`docs/agent/feedback-cases/ENG-35.md`](agent/feedback-cases/ENG-35.md). Insertion point `NetworkClientImpl.applyCommonConfig`; classifier `SentryLogTree.shouldCaptureEvent`.
