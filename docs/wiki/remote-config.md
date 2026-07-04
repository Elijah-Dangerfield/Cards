# Remote config / feature flags

How a flag flips in production with no redeploy, and where every piece lives. Built in-house
(decided 2026-06-19 — no PostHog / Statsig / LaunchDarkly) with a locally-run admin GUI; never a
published site.

## The flow

1. **Flags live in Postgres** — `app_config_values` (migration V75), keyed by `ConfiguredValue.path`
   (e.g. `billing.realPurchasesEnabled`). The server reads them through `PostgresAppConfigSource`
   with a 30s TTL cache, so an edit is live on the next client config refresh — no redeploy.
2. **Targeting resolves server-side** — per-flag rules in `app_config_rules` (V76), evaluated
   first-match-wins by `AppConfigTargetingEngine`. The endpoint returns *resolved* values, so the
   client model never knows targeting exists. Shipped axes: platform, version-code range, country,
   locale, user-id allow/deny, and deterministic FNV-1a % rollout bucketed on
   user-id-or-install-id (ramps are monotonic — nobody flips back off as you raise the %).
   The config route is optionally JWT-authed so user-id targeting works while the kill-switch
   still loads pre-session; the client attaches its bearer best-effort. Every change writes to
   the `app_config_audit` log.
3. **The client refreshes on foreground** (throttled), not on a poll — see the 2026-06-27
   force-update decision in [`decisions.md`](../decisions.md) for why mid-session pushes are
   deliberately out of scope.

## The admin UI

`:apps:admin` — a Compose HTML web app (the repo's only `js` target), run locally via
`./gradlew :apps:admin:jsBrowserDevelopmentRun`. Connects with server URL + `ADMIN_API_TOKEN` +
actor name; edits flag base values, targeting rules, and rollout %, and shows the audit log, all
over the token-gated `/v1/admin/config` API. See `apps/admin/README.md`.

## Known limits

- Unwired targeting axes from the original sketch (OS version, release channel, account type,
  cohort/install date, device class): add to `RuleConditions` + `AppConfigTargetingEngine` when needed.
- The admin UI lists flags that exist in the DB plus free-form "add by path" — it does **not**
  enumerate the client's full `ConfiguredValue` registry (that's client common code the web target
  deliberately doesn't depend on).

## Code pointers

Server: `PostgresAppConfigSource`, `AppConfigTargetingEngine`, `AppConfigAdminRepository`,
`ConfigAdminRoutes`, migrations `V75`/`V76`. Client: `RemoteConfigRemoteDataSource` (best-effort
bearer). Admin: `apps/admin`.
