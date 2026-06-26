# Post-launch

Committed work we intend to do, but **not** before the V1 launch. Distinct from
[`backlog.md`](./backlog.md) (someday/maybe ideas we may never do) and from
[`todo.md`](./todo.md) (the launch punch list). When launch is behind us, items graduate
from here into `todo.md`.

Each item carries enough context to pick up cold. Append; delete when graduated or dropped.

---

## Anti-abuse

### App attestation (Play Integrity / App Attest)
Decided **no for V1** (2026-06-19). Apple App Attest + Google Play Integrity let the server
confirm a request comes from a genuine, untampered build on a real device — real anti-fraud
value on sensitive endpoints (purchase verification, wallet sync, achievement grants). Deferred
because chips aren't cash-out-able, so the cheating payoff is low, and attestation adds setup cost
plus a small legitimate-user failure rate (rooted devices, attestation outages). **Revisit if
backend abuse from forged clients materializes.** If adopted: a server gate on the sensitive routes
+ per-platform client integration.

### Automated ban sweep
Manual banning is the V1 model (triggers + enforcement live in `developer-todo.md` / `todo.md`).
Post-launch, add a **weekly sweep** that flags obvious bad actors above a confidence threshold
(e.g. clear chip-dumping / collusion patterns) and auto-bans the unambiguous ones; everything below
the bar surfaces for manual review rather than auto-acting. Pairs with the reporting feature below.

### In-app reporting + report-threshold auto-ban
Let players report another player (abusive name / chat / emotes, suspected collusion). Post-launch,
once reporting exists, add a rule: **≥ 3 reports against one account within 72 hours → auto-ban**
(reviewable / reversible via the same appeal email as manual bans). Feeds the sweep above.

## Accounts

### Auto-trigger the inactivity-based orphan sweep
Opportunistic orphan deletion is shipped (`DefaultOrphanInstallSweep`, fires on `/v1/me` when a
device re-binds to a different active anon, with the no-purchase / no-meaningful-XP guards from
`decisions.md` 2026-06-19). The ≥-1-year inactivity sweep is also built (`DefaultOrphanAnonymousSweep`,
exposed at `POST /v1/admin/sweep-anonymous-users`) — but it only runs when something hits the route.
Post-launch, wire an automatic trigger (Fly scheduled task, cron, GitHub Actions cron) so the sweep
runs without a manual kick. Low priority: orphan rows are cheap, and the conservative-by-design
guards (no purchases, no high XP, no active room seat) mean a missed sweep just leaks rows, never
deletes someone's progress.

## App platform

### Remote config / feature flags (in-house, local admin GUI) — IMPLEMENTED (2026-06-26)
Decided (2026-06-19) to **build this in-house** (no hosted service like PostHog / Statsig /
LaunchDarkly) with a **locally-run admin web GUI** that edits DB config values directly — never a
published/hosted site, just a GUI run on demand against the config table. Built across three slices:
- **Phase 1 — DB-backed source (done):** `PostgresAppConfigSource` replaced `InMemoryAppConfigSource`
  via the same `@ContributesBinding`. Flags live in `app_config_values` (V75), keyed by
  `ConfiguredValue.path`, with a 30s TTL cache — editable in the Supabase table editor (or the admin
  UI) → flags flip with **no redeploy**, live on the next client config refresh.
- **Phase 2 — targeting + rollout (done):** per-flag rules in `app_config_rules` (V76), evaluated
  server-side first-match-wins by `AppConfigTargetingEngine`; the endpoint still returns *resolved*
  values so the client model is untouched. Axes shipped: platform, version-code range, country,
  locale, user-id allow/deny, and deterministic FNV-1a % rollout bucketed on user-id-or-install-id
  (monotonic ramps). The config route is now optionally JWT-authed so user-id targeting works while
  the kill-switch still loads pre-session; the client attaches its bearer best-effort. Change audit
  log in `app_config_audit`. *(Remaining axes from the original sketch — OS version, release channel,
  account type, cohort/install date, device class — are not yet wired; add to `RuleConditions` +
  `AppConfigTargetingEngine` when needed.)*
- **Phase 3 — local admin UI (done):** `:apps:admin` — a Compose HTML / DOM web app (the repo's first
  `js` target), run via `./gradlew :apps:admin:jsBrowserDevelopmentRun`. Connects with server URL +
  `ADMIN_API_TOKEN` + actor, edits flag base values + targeting rules + rollout %, and shows the
  audit log, all over the token-gated `/v1/admin/config` API. **Local-only**; publishing behind a VPN
  stays a future option. *(It lists flags that exist in the DB + free-form "add by path"; it does
  **not** enumerate the client's full `ConfiguredValue` registry — that's client common code this web
  target deliberately doesn't depend on. Wire that later if discoverability matters.)*

Code: server — `PostgresAppConfigSource`, `AppConfigTargetingEngine`, `AppConfigAdminRepository`,
`ConfigAdminRoutes`, migrations `V75`/`V76`; client — `RemoteConfigRemoteDataSource` (best-effort
bearer); admin — `apps/admin` (see its `README.md`).
