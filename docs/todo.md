# TODO

**Last reviewed:** 2026-07-11 (todo-maintainer) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

The live punch list of actionable engineering work. Every item is something a worker can pick up and ship.

**Build the best thing, not the smallest change.** This is a greenfield, unshipped codebase — the goal is scalable, maintainable, production-ready systems. When you pick an item up, take a step back and ask what's genuinely best for the project and the user, then build that, even if it means restructuring or rebuilding something already here. Don't stack a minimal patch on top of what exists. Full rule: AGENTS.md → Coding Guidelines and [`docs/agent/worker-prompt.md`](agent/worker-prompt.md) → Picking work.

**Fixing a bug? Reproduce it with a failing test first.** Red (the test fails *because of the bug*), then green (the fix makes it pass). It proves you found the real cause, not a guess, and leaves a permanent regression guard. Can't reproduce it in a test? The harness is missing something — build that first. See AGENTS.md → Coding Guidelines.

**Minimum viable context.** Every item is one bold title + at most ~3 short lines (Problem / Acceptance / Hints). No status archaeology — don't narrate what already shipped, what's "locked," or which sub-part landed when. If a sub-part ships, delete that clause. A bullet a human can't skim in five seconds is too long.

**Item IDs.** Every item carries a stable ID — section prefix + number (`PROG-1`, `AUTH-3`, `MP-2`). IDs never get reused: when an item ships or moves to backlog, its number retires with it. Use the ID when referring to an item in commits, PRs, or other docs.

**ID prefixes:** `PROG` (progression / XP / stats), `AUTH` (auth + onboarding), `GAME` (gameplay + table UX), `SHOP` (consumables + rewards), `SOC` (social graph), `ROOM` (rooms UI), `MP` (multiplayer hardening), `ENG` (engineering / structural), `BILL` (billing), `ECON` (chip economy integrity).

**Priority tags** (every item carries one; bias toward P0 first):

- `[P0]` — V1 ship-blocker or load-bearing for other work.
- `[P1]` — Real value, not blocking.
- `[P2]` — Lower urgency, still worker-pickable. Many need a directional call — make a recommendation, ship a slice, let the reviewer course-correct.

Everything here is worker-pickable. Human-only work (device QA, dashboard config, content, product decisions) lives in [`developer-todo.md`](./developer-todo.md). Deferred ideas live in [`backlog.md`](./backlog.md) — when an item gets descoped or doesn't fit V1, move it there, don't delete it.

## ENG — engineering / structural

- **ENG-18 `[P1]` Client app events: verify the Grafana pipe end-to-end, then instrument the full taxonomy.** Problem: the `logEvent` → `GrafanaLogTree` pipeline (plan PR 1) is built and tested but ships with blank OTLP credentials, and only the starter events (`app.launched`, `room.*`, `hand.completed`, `purchase.*`) are instrumented — the matchmaking/onboarding/reliability funnels are still dark.
  **Acceptance:** with the owner-pasted logs:write token in `GrafanaCloud` (`libraries/telemetry/impl/.../GrafanaAppEvents.kt`), run the plan's Verification section against dev Loki (correlation query, kill-switch drill, offline drill); then sweep the Part A taxonomy (plan PR 2) and document event names in `docs/wiki/app-events.md`.
  **Hints:** plan at [`docs/plans/client-app-events-otel.md`](plans/client-app-events-otel.md); owner token prerequisite tracked in developer-todo. PR 3 (Warn+ log forwarding behind a flag) and PR 4 (dashboards) follow the sweep.

- **ENG-19 `[P2]` Grafana users-and-sessions dashboard.** Problem: there is no view of users, platforms, or session behavior at all.
  **Acceptance:** a new users dashboard shows player counts by platform, session counts and lengths, and anomalies like the longest session, powered by ENG-18 events.
  **Hints:** ENG-18 events land in Loki; blocked in practice until ENG-18's client events flow.

