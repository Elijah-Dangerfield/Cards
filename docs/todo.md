# TODO

**Last reviewed:** 2026-07-10 (todo-maintainer) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

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

- **ENG-18 `[P1]` Client app events over KMP OpenTelemetry: `logEvent` extension + GrafanaLogTree, direct to Grafana Cloud.** Problem: there are no product analytics. Anything only the client sees (matchmaking back-outs, onboarding drop-off, bot games, backend-unreachable errors) never reaches Grafana.
  **Acceptance:** implement the approved plan in [`docs/plans/client-app-events-otel.md`](plans/client-app-events-otel.md) — a `KLog.logEvent(name, attrs)` extension whose entries a new `GrafanaLogTree` filters and ships via opentelemetry-kotlin (0.5.0) straight to the Grafana Cloud OTLP gateway (deliberately not through our backend, so events survive backend outages), with remote-config kill switch + per-session sampling, tests on the in-memory exporter, and the plan's PR 1 starter events flowing end-to-end into dev Loki.
  **Hints:** plan supersedes the earlier server-relay direction (owner call, 2026-07-10): a hard-coded logs:write-only token is acceptable, Sentry-DSN precedent. Owner prerequisite: mint that token (tracked in developer-todo). Event taxonomy for the follow-up instrumentation sweep and dashboards (partly overlapping ENG-19) is Part A of the plan.

- **ENG-19 `[P2]` Grafana users-and-sessions dashboard.** Problem: there is no view of users, platforms, or session behavior at all.
  **Acceptance:** a new users dashboard shows player counts by platform, session counts and lengths, and anomalies like the longest session, powered by ENG-18 events.
  **Hints:** ENG-18 events land in Loki; blocked in practice until ENG-18's client events flow.

- **ENG-22 `[P1]` Surface feedback / bug-report submit failures instead of faking success. (proposed 2026-07-10)** Problem: `FeedbackViewModel` and `BugReportViewModel` end the submit with `eitherWay {}` — a failed send still shows the "thanks" snackbar, bumps `feedbacksGiven`, and navigates back, silently discarding the user's typed report (the compounding half of the 2026-07-09 lost-feedback incident; ENG-16 only fixed the sampling half).
  **Acceptance:** a failed submit keeps the user on the screen with their text intact and shows an error with retry; success path unchanged. Failing test first per repo convention.
  **Hints:** `features/profile/impl/.../feedback/FeedbackViewModel.kt:88` + `.../bugreport/BugReportViewModel.kt:96`; the repository already returns a `Catching` result — stop discarding it. `EditProfileViewModel`'s error surfacing is the sibling pattern.

- **ENG-23 `[P2]` Refresh `docs/wiki/wallet.md` to the PROG-11 derived-balance model. (proposed 2026-07-10)** Problem: the page still describes the pre-PROG-11 client — "trusts the server's authoritative balance on every sync" and "silently resets… (no user-facing surface yet)" — but the client now derives display balance as server snapshot + pending outbox, and `InsufficientChips` surfaces an error snackbar.
  **Acceptance:** the client-side claims and the `ChipsRepositoryImpl` key-file pointer match the code shipped in commit 7c1b5488.

