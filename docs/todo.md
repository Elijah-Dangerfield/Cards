# TODO

**Last reviewed:** 2026-07-08 (todo-maintainer) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

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

- **PROG-11 `[P1]` Displayed balance must fold pending wallet events; make cold-boot reconcile reliable.** Problem: grants write a persisted `wallet_events` outbox row + optimistic `ChipsEntity.balance`, but `sync()` overwrites the balance with server truth even when pending rows remain unposted, and `UserScopedSyncCoordinator` skips cold-boot foreground (expects auth-resolve `UserChanged` to own it) — the owner's 07-09 relaunch showed the bare server balance and the grants didn't land server-side until sign-in fired a sync ~30 min later.
  **Acceptance:** failing-first tests — (a) displayed balance = server snapshot + SUM(pending outbox) so no sync ordering can "vanish" earned chips; (b) cold-boot relaunch with a persisted session flushes the outbox without a fresh sign-in (find why relaunch didn't: missed `UserChanged` on session restore, or silent sync failure).
  **Hints:** `ChipsRepositoryImpl.sync()` `setBalance(response.balance)` overwrite + `observeBalance()` (no fold); `UserScopedSyncCoordinator` cold-boot skip; case `docs/agent/feedback-cases/2026-07-09-chips-vanish-on-restart.md`.

## ECON — chip economy integrity

- **ECON-1 `[P1]` Close the wallet-ledger gaps and add a conservation check.** Problem: starter grants seed `wallets.balance` with no `wallet_events` row — prod SUM(deltas) is 126,000 vs supply 146,000, a 20,000 unexplained pile; any unledgered mutation makes the economy dashboard unauditable.
  **Acceptance:** every balance mutation writes a `wallet_events` row (audit all paths: starter grant, MP settlement, bust protection, shop, admin); a conservation check (`supply == SUM(delta)`) exists as a test + alertable query and passes on prod after backfilling starter rows.
  **Hints:** server wallet write paths; schema visible in the `cards-economy` dashboard queries; case `docs/agent/feedback-cases/2026-07-09-chips-vanish-on-restart.md`.

- **ECON-2 `[P2]` Admin chip adjustment: endpoint + GH action writing audited ledger entries.** Problem: no way to reset/adjust a prod balance (owner's wallet holds 125k sandbox-minted chips) without raw SQL that bypasses the ledger.
  **Acceptance:** admin-authed endpoint applies a delta with reason `admin_adjustment:<note>` through the normal ledger path; a GH workflow wraps it for manual runs.
  **Hints:** existing `/v1/admin/*` routes + auth; depends on ECON-1's single write path.

## BILL — billing

- **BILL-6 `[P1]` Record the StoreKit environment on purchases; segment sandbox out of economy metrics.** Problem: TestFlight IAPs are always sandbox (free) — the owner's two test purchases minted 125,000 unpaid chips recorded as real revenue (`billing_transactions` has no environment column). Permanent issue: TestFlight users always sandbox-purchase, even post-launch.
  **Acceptance:** environment (`sandbox`|`production`) persisted per transaction and reflected in the wallet-events reason (or a column); `cards-economy` dashboard segments/excludes sandbox mints from supply + revenue panels.
  **Hints:** the server already knows the environment at JWS verification (`APPLE_STORE_ENVIRONMENT` in `ServerConfig`, currently `Sandbox` on BOTH Fly apps — must flip prod to `Production` at App Store launch, ideally verifying Production-with-Sandbox-fallback so TestFlight keeps working); `StoreKitBillingClient` + the server redemption route.

## ENG — engineering / structural

- **ENG-17 `[P1]` Prod server ships no telemetry — wire OTLP export on the prod Fly app.** Problem: Loki/Tempo contain only `deployment_environment=dev` for the entire last week; the 07-09 prod session left zero backend logs/traces (triage had to go through the Postgres datasource instead).
  **Acceptance:** prod requests produce logs and traces in Grafana labeled `deployment_environment=prod`, verified with one live prod request.
  **Hints:** `installOpenTelemetry` in `apps/server/.../plugins/Telemetry.kt` (logs "exporter=otlp-http"); diff Fly secrets/env between the dev and prod `cards-server` apps.

- **ENG-18 `[P1]` Client product events over OTel: `logger.event()` tree + server OTLP relay.** Problem: no product analytics — client-only happenings (bot games, sessions, funnels) never reach Grafana.
  **Acceptance:** `logger.event(name, props)` flows client → server relay (session-token auth; server stamps verified user + environment) → Loki, with the OTel SDK's batching/disk-cache/retry; the Sentry tree records the same events as breadcrumbs; first events: `bot_game_played`, `session_start`/`session_end`.
  **Hints:** KMP OTel SDK (Embrace opentelemetry-kotlin); KLog tree pattern in `AppTelemetry`; relay is a dumb OTLP passthrough route — do NOT ship Grafana Cloud credentials in the app.

- **ENG-19 `[P2]` Grafana dashboards: chip provenance + users/sessions.** Problem: the economy dashboard can't segment sandbox vs real (BILL-6) and there's no users/platform/sessions view.
  **Acceptance:** economy dashboard gains a source-breakdown (pie over `wallet_events.reason`) + sandbox segmentation; a users dashboard shows counts by platform, session count/length, and anomalies (longest session) off ENG-18 events.
  **Hints:** `cards-economy` dashboard JSON; ENG-18 events in Loki; depends on ECON-1 + BILL-6 for honest numbers.

- **ENG-15 `[P2]` Rename Virtu-branded ObjC bridge names to Cards.** Problem: the Kotlin↔Swift bridge still exports `VirtuNativeViewFactory` / `VirtuNativeAppleSignInButtonKind` / `VirtuNativeAppleSignInButtonStyle` (`@ObjCName(..., exact = true)` in `libraries/ui/src/iosMain/.../nativeviews/NativeViewFactory.kt`, referenced from `apps/ios/iosApp/iOSApp.swift` and `Platform/IOSNativeViewFactory.swift`) — leftover branding from two template generations ago. Acceptance: prefix renamed to `Cards*` in the Kotlin annotations and all Swift references; while there, prune anything in the bridge Cards doesn't use (camera code is already gone — check nothing else is dead); verified by an iOS simulator build of the `iosApp` scheme (Swift compiles against the generated framework header — Kotlin compilation alone proves nothing) with zero `Virtu` hits left in the generated `ComposeApp.h`. Hints: the same rename shipped in KMPTemplate main as `90a9eb5` — mirror it.
