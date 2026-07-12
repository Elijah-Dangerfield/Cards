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
   client model never knows targeting exists. Shipped axes (`RuleConditions` in
   `AppConfigTargeting.kt`): platform, version-code range, **semantic app-version bounds**
   (`minAppVersion`/`maxAppVersion` with per-bound inclusive flags, compared by `SemVer`
   precedence — `> 1.0.1` is `min = "1.0.1", minInclusive = false`; a client that doesn't send
   an app version fails a set bound), country, locale, user-id allow/deny, and deterministic
   FNV-1a % rollout bucketed on user-id-or-install-id (ramps are monotonic — nobody flips back
   off as you raise the %). The config route is optionally JWT-authed so user-id targeting works
   while the kill-switch still loads pre-session; the client attaches its bearer best-effort.
   Every change writes to the `app_config_audit` log.
3. **The client refreshes on foreground** (throttled), not on a poll — see the 2026-06-27
   force-update decision in [`decisions.md`](../decisions.md) for why mid-session pushes are
   deliberately out of scope.

## The admin UI

`:apps:admin` — a Compose HTML web app (the repo's only `js` target), run locally via
`./gradlew :apps:admin:jsBrowserDevelopmentRun`. Connects with server URL + `ADMIN_API_TOKEN` +
actor name; edits flag base values, targeting rules, and rollout %, and shows the audit log, all
over the token-gated `/v1/admin/config` API. See `apps/admin/README.md`.

Two surfaces close the "what does the client actually see" loop:

- **Per-version manifest** (`app_config_manifest`, V77) — the client's in-code `ConfiguredValue`
  registry, captured per app version. The JS module can't read client common code, so the build
  exports it from the committed `apps/admin/config-manifest-registry.json`
  (`exportConfigManifest`, structurally validated; `ConfigManifestDriftTest` in
  `:apps:integration` fails CI if it drifts from the real classes) and the server-deploy
  workflows upload it after each deploy (`PUT /v1/admin/config/manifest`). The Versions tab
  shows what each build shipped with — the in-code default a remote override replaces.
- **Resolve preview** (`POST /v1/admin/config/resolve`) — pick a target (platform, version,
  country, user…) and see, per flag, in-code default → DB base → which rule won → resolved
  value. The flag list is the **union** of the live DB flags and the version's manifest, so a
  flag that only exists in code still shows up. Adding a rule for a manifest-only flag seeds
  its DB row from the shipped default rather than 409ing.

## Known limits

- Unwired targeting axes from the original sketch (OS version, release channel, account type,
  cohort/install date, device class): add to `RuleConditions` + `AppConfigTargetingEngine` when needed.
- The manifest covers scalar `ConfiguredValue`s only — composite (`JsonConfigValue`) flags are
  intentionally omitted from the registry, so the Versions tab and resolve preview won't show
  their in-code defaults.

## Code pointers

Server: `PostgresAppConfigSource`, `AppConfigTargetingEngine` + `SemVer`, `AppConfigTargeting.kt`
(`RuleConditions`), `AppConfigAdminRepository`, `AppConfigManifestRepository`,
`ConfigAdminRoutes`, migrations `V75`–`V77`. Client: `RemoteConfigRemoteDataSource` (best-effort
bearer). Admin: `apps/admin` (registry: `config-manifest-registry.json`, export task
`exportConfigManifest`, drift guard `ConfigManifestDriftTest`).
