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

## PROG — progression / XP / stats

- **PROG-11 `[P1]` Chip balance must stay optimistic through every sync ordering (spec-driven).** Problem: a sync posts every pending wallet event in one request, then unconditionally overwrites the local balance with the server's number. Chips granted while that request is in flight get wiped from the display until the next sync, and events the server rejects silently shrink the balance with no message to the user. The owner's 07-09 "my 500 chips vanished" report is this family of bug.
  **Acceptance:** run a subagent investigation first, write the plan into the case file, then implement it. The shipped behavior must guarantee two things, each pinned by a failing-first test: the displayed balance always equals the server's last snapshot plus pending local events (so no sync ordering can make earned chips disappear), and a rejected event tells the user instead of silently dropping their balance.
  **Hints:** `ChipsRepositoryImpl.syncLocked` (the window between `getAll()` and `setBalance()`) and `observeBalance` (reads the raw entity, no pending fold). The "why didn't relaunch trigger a sync at all" half of the incident belongs to ENG-20. Case: `docs/agent/feedback-cases/2026-07-09-chips-vanish-on-restart.md`.

## ENG — engineering / structural

- **ENG-21 `[P2]` Close the user-switch clear window: an old user's in-flight sync can still race `clearFor`.** Problem: `SupabaseAuthRepositoryImpl.emitLocked` awaits `userScopedDataReset.clearFor(previous)` before emitting the new user, but a sync already in flight for the old user is only cancelled when the new emission reaches the sync loops - its writes can land mid-clear. ENG-20's cancel-on-key-change narrowed this pre-existing window; it is not closed.
  **Acceptance:** a user-switch test proves no old-user write can land after `clearFor` starts (e.g. the clear awaits cancellation of that user's sync jobs).
  **Hints:** `runWhen` in `libraries/flowroutines`, `UserScopedSyncCoordinator`; filed as a follow-up in `docs/plans/eng-20-runwhen-triggers.md`.

- **ENG-18 `[P1]` Client app events over KMP OpenTelemetry: `logEvent` extension + GrafanaLogTree, direct to Grafana Cloud.** Problem: there are no product analytics. Anything only the client sees (matchmaking back-outs, onboarding drop-off, bot games, backend-unreachable errors) never reaches Grafana.
  **Acceptance:** implement the approved plan in [`docs/plans/client-app-events-otel.md`](plans/client-app-events-otel.md) — a `KLog.logEvent(name, attrs)` extension whose entries a new `GrafanaLogTree` filters and ships via opentelemetry-kotlin (0.5.0) straight to the Grafana Cloud OTLP gateway (deliberately not through our backend, so events survive backend outages), with remote-config kill switch + per-session sampling, tests on the in-memory exporter, and the plan's PR 1 starter events flowing end-to-end into dev Loki.
  **Hints:** plan supersedes the earlier server-relay direction (owner call, 2026-07-10): a hard-coded logs:write-only token is acceptable, Sentry-DSN precedent. Owner prerequisite: mint that token (tracked in developer-todo). Event taxonomy for the follow-up instrumentation sweep and dashboards (partly overlapping ENG-19) is Part A of the plan.

- **ENG-19 `[P2]` Grafana users-and-sessions dashboard.** Problem: there is no view of users, platforms, or session behavior at all (the economy dashboard's chip-source breakdown and sandbox/real split shipped with BILL-6/ECON-1).
  **Acceptance:** a new users dashboard shows player counts by platform, session counts and lengths, and anomalies like the longest session, powered by ENG-18 events.
  **Hints:** ENG-18 events land in Loki; blocked in practice until ENG-18's client events flow.

- **ENG-15 `[P2]` Rename Virtu-branded ObjC bridge names to Cards.** Problem: the Kotlin↔Swift bridge still exports `VirtuNativeViewFactory` / `VirtuNativeAppleSignInButtonKind` / `VirtuNativeAppleSignInButtonStyle` (`@ObjCName(..., exact = true)` in `libraries/ui/src/iosMain/.../nativeviews/NativeViewFactory.kt`, referenced from `apps/ios/iosApp/iOSApp.swift` and `Platform/IOSNativeViewFactory.swift`) — leftover branding from two template generations ago. Acceptance: prefix renamed to `Cards*` in the Kotlin annotations and all Swift references; while there, prune anything in the bridge Cards doesn't use (camera code is already gone — check nothing else is dead); verified by an iOS simulator build of the `iosApp` scheme (Swift compiles against the generated framework header — Kotlin compilation alone proves nothing) with zero `Virtu` hits left in the generated `ComposeApp.h`. Hints: the same rename shipped in KMPTemplate main as `90a9eb5` — mirror it.
