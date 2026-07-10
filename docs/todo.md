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

- **PROG-11 `[P1]` Chip balance must stay optimistic through every sync ordering (spec-driven).** Problem: a sync posts every pending wallet event in one request, then unconditionally overwrites the local balance with the server's number. Chips granted while that request is in flight get wiped from the display until the next sync, and events the server rejects silently shrink the balance with no message to the user. The owner's 07-09 "my 500 chips vanished" report is this family of bug.
  **Acceptance:** run a subagent investigation first, write the plan into the case file, then implement it. The shipped behavior must guarantee two things, each pinned by a failing-first test: the displayed balance always equals the server's last snapshot plus pending local events (so no sync ordering can make earned chips disappear), and a rejected event tells the user instead of silently dropping their balance.
  **Hints:** `ChipsRepositoryImpl.syncLocked` (the window between `getAll()` and `setBalance()`) and `observeBalance` (reads the raw entity, no pending fold). The "why didn't relaunch trigger a sync at all" half of the incident belongs to ENG-20. Case: `docs/agent/feedback-cases/2026-07-09-chips-vanish-on-restart.md`.

## ECON — chip economy integrity

- **ECON-1 `[P1]` Close the wallet-ledger gaps and add a conservation check.** Problem: starter grants set the wallet balance without writing a `wallet_events` row, so the ledger can't explain the money. Prod balances add up to 146,000 chips but the ledger only accounts for 126,000. Any mutation that skips the ledger makes the economy dashboard unauditable.
  **Acceptance:** every balance change writes a ledger row (audit every path: starter grant, multiplayer settlement, bust protection, shop, admin). A conservation check (total balances equal the ledger sum) exists as a test and an alertable query, and passes on prod once the missing starter rows are backfilled.
  **Hints:** server wallet write paths; the schema is visible in the `cards-economy` dashboard queries; case `docs/agent/feedback-cases/2026-07-09-chips-vanish-on-restart.md`.

- **ECON-2 `[P2]` Admin chip adjustment: endpoint + GitHub action writing audited ledger entries.** Problem: the only way to reset or adjust a prod balance today is raw SQL that bypasses the ledger. The owner's wallet holds 125,000 test-purchase chips that need cleaning up.
  **Acceptance:** an admin-authenticated endpoint applies a delta with reason `admin_adjustment:<note>` through the normal ledger path, and a GitHub workflow wraps it for manual runs.
  **Hints:** existing `/v1/admin/*` routes and their auth; builds on ECON-1's single write path.

## BILL — billing

- **BILL-6 `[P1]` Record the StoreKit environment on purchases; segment sandbox out of economy metrics.** Problem: TestFlight purchases always run against Apple's sandbox, so testers get chip packs for free, and those purchases are currently recorded as real. The owner's two test buys minted 125,000 unpaid chips that now read as revenue (`billing_transactions` has no environment column). This never goes away on its own: TestFlight testers exist after launch too.
  **Acceptance:** each transaction persists which environment (`sandbox` or `production`) verified it, the wallet ledger reflects it, and the `cards-economy` dashboard excludes or segments sandbox mints in the supply and revenue panels.
  **Hints:** the server already knows the environment when it verifies the receipt (`APPLE_STORE_ENVIRONMENT` in `ServerConfig`; note it is `Sandbox` on BOTH Fly apps today and must flip prod to `Production` at App Store launch, ideally verifying production first with a sandbox fallback so TestFlight keeps working). See `StoreKitBillingClient` and the server redemption route.

## ENG — engineering / structural

- **ENG-20 `[P1]` Sync triggers should read auth as state, not chase auth events off the bus.** Problem: `UserScopedSyncCoordinator` only syncs when it catches a `UserChanged` event from a shared bus that replays a single event. Any later boot event (like the cold-boot foreground) evicts that replay slot, so a fast app launch can miss the sign-in trigger entirely and never sync. The coordinator also ignores `ConnectivityRegained`, and a sync blocked because auth wasn't ready yet is never retried. This fragility is the prime suspect for why the owner's 07-09 relaunch never uploaded its pending chips.
  **Acceptance:** the coordinator learns "someone is signed in" from auth exposed as a `StateFlow`, so a subscriber always sees the current user no matter when it subscribes, combined with warm-foreground and connectivity-regained edges. Syncs blocked on auth retry once auth is ready. A test covers the boot ordering where sign-in resolves before the coordinator subscribes (the current fake-bus test can't hit it).
  **Hints:** `SupabaseAuthRepositoryImpl.state` is already a replay-1 SharedFlow, one type change away from `StateFlow`. `AppEventDispatcher` (replay=1, DROP_OLDEST) is the eviction hazard. `NetworkCall.shortCircuitOrNull` already no-ops calls when signed out, which is what makes state-based triggering safe on fresh installs (no wasted requests). Ranked failure modes in case `docs/agent/feedback-cases/2026-07-09-chips-vanish-on-restart.md`.

- **ENG-17 `[P1]` Wire telemetry export on the prod Fly app.** Problem: the prod server sends its logs, traces, and errors to stdout instead of Grafana and Sentry. The `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_EXPORTER_OTLP_HEADERS`, and `SENTRY_DSN` secrets were only ever set on the dev Fly app, so Grafana holds zero prod data and prod server errors go nowhere.
  **Acceptance:** prod requests produce logs and traces in Grafana labeled `deployment_environment=prod` and prod server errors reach Sentry, verified with one live prod request.
  **Hints:** set the three secrets on `cards-server-prod` (OTLP values from Grafana Cloud, same Sentry DSN as dev). The environment label derives automatically from `FLY_APP_NAME`; see `installOpenTelemetry` in `apps/server/.../plugins/Telemetry.kt`.

- **ENG-18 `[P1]` Client product events over OpenTelemetry: `logger.event()` tree plus a server relay.** Problem: there are no product analytics. Anything only the client sees (bot games played, session starts and ends, screen funnels) never reaches Grafana.
  **Acceptance:** `logger.event(name, props)` on the client ships through the KMP OTel SDK (which brings batching, disk cache, and retry) to a relay route on our server; the relay checks the session token, stamps the verified user id and environment, and forwards to Grafana. The Sentry tree records the same events as breadcrumbs for crash context. First events: `bot_game_played`, `session_start`, `session_end`.
  **Hints:** Embrace's opentelemetry-kotlin KMP SDK; the KLog tree pattern in `AppTelemetry`; the relay is a dumb OTLP passthrough. Never ship Grafana Cloud credentials inside the app binary.

- **ENG-19 `[P2]` Grafana dashboards: where chips come from, plus users and sessions.** Problem: the economy dashboard can't tell sandbox chips from real ones, and there is no view of users, platforms, or session behavior at all.
  **Acceptance:** the economy dashboard gains a chip-source breakdown (pie over `wallet_events.reason`) and a sandbox/real split; a new users dashboard shows player counts by platform, session counts and lengths, and anomalies like the longest session, powered by ENG-18 events.
  **Hints:** `cards-economy` dashboard JSON; ENG-18 events land in Loki; depends on ECON-1 and BILL-6 for numbers worth graphing.

- **ENG-15 `[P2]` Rename Virtu-branded ObjC bridge names to Cards.** Problem: the Kotlin↔Swift bridge still exports `VirtuNativeViewFactory` / `VirtuNativeAppleSignInButtonKind` / `VirtuNativeAppleSignInButtonStyle` (`@ObjCName(..., exact = true)` in `libraries/ui/src/iosMain/.../nativeviews/NativeViewFactory.kt`, referenced from `apps/ios/iosApp/iOSApp.swift` and `Platform/IOSNativeViewFactory.swift`) — leftover branding from two template generations ago. Acceptance: prefix renamed to `Cards*` in the Kotlin annotations and all Swift references; while there, prune anything in the bridge Cards doesn't use (camera code is already gone — check nothing else is dead); verified by an iOS simulator build of the `iosApp` scheme (Swift compiles against the generated framework header — Kotlin compilation alone proves nothing) with zero `Virtu` hits left in the generated `ComposeApp.h`. Hints: the same rename shipped in KMPTemplate main as `90a9eb5` — mirror it.
