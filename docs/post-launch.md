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

### Conservative inactivity-based orphan deletion
V1 deletes an abandoned anonymous account only opportunistically — when its device is now bound to a
*different* active anon account (the "one in use, one unreachable" case), and only if it has **no
real-money purchase** and **no meaningful XP** (see `decisions.md` 2026-06-19). The other deletion
trigger — **≥ 1 year fully inactive** — is deferred here because a never-reused account is never
re-visited by the opportunistic path, so it needs a **scheduled sweep**. Low priority: orphan rows
are cheap, and the whole point of the conservative model is that we'd rather leak rows than ever
delete someone's progress by accident. Same hard guards apply: never delete with purchases, never
delete a high-XP account.

## App platform

### Remote config / feature flags (in-house, local admin GUI)
Today app config is hardcoded server-side (`InMemoryAppConfigSource`) — every change is a redeploy,
and a value is all-or-nothing for every user. Decided (2026-06-19) to **build this in-house** (no
hosted service like PostHog / Statsig / LaunchDarkly) with a **locally-run admin web GUI** that edits
DB config values directly — never a published/hosted site, just a GUI Elijah runs on demand against
the config table. Ship in slices:
- **Phase 1 — DB-backed source:** `PostgresAppConfigSource` (drops in for `InMemoryAppConfigSource`
  via the same `@ContributesBinding`), reads a `key → value` tree from Postgres with a short TTL
  cache. Editable in the Supabase table editor → flags flip with **no redeploy**, live on the next
  client config refresh. (Cheap + high value — could be pulled forward pre-launch if redeploy pain
  bites.)
- **Phase 2 — targeting + rollout:** per-flag rules evaluated server-side in `GET /v1/app-config`
  (endpoint already returns *resolved* values, so the client model is untouched). Axes: platform,
  app version, user-id allow/deny, location, locale, OS version, release channel, account type,
  install/cohort date, device class. Deterministic % bucketing (`hash(userId + flagKey) % 100`) for
  ramps + A/B. Change audit log.
- **Phase 3 — local admin UI:** the on-demand local web app — lists every flag (from the
  `ConfiguredValue` registry), shows the value served per app version / audience, and edits values +
  rules + rollout %.

The seam already exists: `AppConfigSource` (server) + `ConfiguredValue` / `AppConfigMap` (client);
some eval inputs live in `ClientHeaders` (install id, platform, app version) + the JWT.
